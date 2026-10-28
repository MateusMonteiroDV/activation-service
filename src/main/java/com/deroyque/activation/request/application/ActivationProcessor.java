package com.deroyque.activation.request.application;

import com.deroyque.activation.provider.ProviderClient;
import com.deroyque.activation.provider.ProviderProperties;
import com.deroyque.activation.provider.ProviderResult;
import com.deroyque.activation.request.domain.ActivationRequest;
import com.deroyque.activation.request.domain.ActivationRequestRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Component
@EnableConfigurationProperties(ProviderProperties.class)
public class ActivationProcessor {

    private static final Logger log = LoggerFactory.getLogger(ActivationProcessor.class);

    private final ActivationRequestRepository repository;
    private final ProviderClient providerClient;
    private final ProviderProperties properties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate txTemplate;
    private final Random random = new Random();

    public ActivationProcessor(ActivationRequestRepository repository,
                               ProviderClient providerClient,
                               ProviderProperties properties,
                               Clock clock,
                               MeterRegistry meterRegistry,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.providerClient = providerClient;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Ciclo principal: busca solicitações PENDING com SKIP LOCKED e as envia ao provedor.
     */
    @Scheduled(fixedDelayString = "${activation.processor.interval-ms:2000}")
    public int processDueRequests() {
        List<UUID> claimedIds = claimDueBatch();
        if (claimedIds.isEmpty()) {
            return 0;
        }

        for (UUID id : claimedIds) {
            processSingleRequest(id);
        }
        return claimedIds.size();
    }

    /**
     * Reconciliação: recupera solicitações que ficaram presas em PROCESSING se uma instância morrer no meio.
     */
    @Scheduled(fixedDelayString = "${activation.reconciler.interval-ms:60000}")
    public int reconcileStuckRequests() {
        Instant stuckThreshold = now().minus(properties.stuckTimeout());
        List<UUID> stuckIds = txTemplate.execute(status -> {
            var stuck = repository.lockStuckInProcessing(stuckThreshold, properties.batchSize());
            return stuck.stream().map(ActivationRequest::getId).toList();
        });

        if (stuckIds == null || stuckIds.isEmpty()) {
            return 0;
        }

        log.warn("Detectadas {} solicitações presas em PROCESSING", stuckIds.size());
        for (UUID id : stuckIds) {
            txTemplate.executeWithoutResult(status -> {
                repository.findById(id).ifPresent(req -> {
                    Instant now = now();
                    if (req.getAttempts() >= properties.maxAttempts()) {
                        req.fail("Timeout do processamento local (stuck in PROCESSING)", now);
                    } else {
                        Instant retryAt = now.plus(calculateBackoff(req.getAttempts()));
                        req.scheduleRetry("Recuperado da reconciliação (stuck in PROCESSING)", retryAt, now);
                    }
                    repository.save(req);
                });
            });
        }
        return stuckIds.size();
    }

    private List<UUID> claimDueBatch() {
        return txTemplate.execute(status -> {
            Instant now = now();
            var batch = repository.lockDueForProcessing(now, properties.batchSize());
            for (var req : batch) {
                req.startProcessing(now);
            }
            repository.saveAll(batch);
            return batch.stream().map(ActivationRequest::getId).toList();
        });
    }

    private void processSingleRequest(UUID id) {
        var request = repository.findById(id).orElse(null);
        if (request == null) {
            return;
        }

        if (request.getCorrelationId() != null) {
            MDC.put("correlationId", request.getCorrelationId());
        }
        try {
            var result = providerClient.activate(
                    request.getId(),
                    request.getExternalId(),
                    request.getCustomerId(),
                    request.getProductCode()
            );

            applyResult(id, result);
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void applyResult(UUID id, ProviderResult result) {
        txTemplate.executeWithoutResult(status -> {
            repository.findById(id).ifPresent(req -> {
                Instant now = now();
                switch (result) {
                    case ProviderResult.Success success -> {
                        req.complete(now);
                        meterRegistry.counter("activation.requests.completed").increment();
                        log.info("Solicitação concluída com sucesso id={}", id);
                    }
                    case ProviderResult.PermanentFailure failure -> {
                        req.fail(failure.message(), now);
                        meterRegistry.counter("activation.requests.failed", "reason", "permanent").increment();
                        log.warn("Solicitação falhou definitivamente id={} status={}", id, failure.statusCode());
                    }
                    case ProviderResult.RetryableFailure failure -> {
                        if (req.getAttempts() >= properties.maxAttempts()) {
                            String msg = "Limite de " + properties.maxAttempts() + " tentativas atingido: " + failure.message();
                            req.fail(msg, now);
                            meterRegistry.counter("activation.requests.failed", "reason", "max_attempts").increment();
                            log.error("Solicitação atingiu tentativas máximas id={}", id);
                        } else {
                            Duration delay = failure.retryAfter() != null
                                    ? failure.retryAfter()
                                    : calculateBackoff(req.getAttempts());
                            Instant retryAt = now.plus(delay);
                            req.scheduleRetry(failure.message(), retryAt, now);
                            meterRegistry.counter("activation.requests.retried").increment();
                            log.info("Solicitação reagendada para {} (tentativa {}) id={}", retryAt, req.getAttempts(), id);
                        }
                    }
                }
                repository.save(req);
            });
        });
    }

    public Duration calculateBackoff(int attempt) {
        double exp = Math.pow(properties.backoffMultiplier(), Math.max(0, attempt - 1));
        long baseMillis = (long) (properties.initialBackoff().toMillis() * exp);
        long maxMillis = properties.maxBackoff().toMillis();
        long cappedMillis = Math.min(baseMillis, maxMillis);

        // Full Jitter: randomiza entre 50% e 100% do tempo de backoff
        double jitterFactor = 0.5 + (random.nextDouble() * 0.5);
        long finalMillis = (long) (cappedMillis * jitterFactor);
        return Duration.ofMillis(Math.max(properties.initialBackoff().toMillis() / 2, finalMillis));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
