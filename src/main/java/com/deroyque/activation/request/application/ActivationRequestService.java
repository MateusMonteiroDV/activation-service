package com.deroyque.activation.request.application;

import com.deroyque.activation.request.domain.ActivationRequest;
import com.deroyque.activation.request.domain.ActivationRequestRepository;
import com.deroyque.activation.request.domain.RequestStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class ActivationRequestService {

    private static final Logger log = LoggerFactory.getLogger(ActivationRequestService.class);

    private final ActivationRequestRepository repository;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public ActivationRequestService(ActivationRequestRepository repository, Clock clock, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Sem {@code @Transactional} de propósito: o INSERT roda na própria transação do repositório.
     * Se ele violar a constraint única, essa transação é descartada e a busca do registro existente
     * roda numa transação nova e limpa.
     */
    public CreateOutcome create(CreateActivationCommand command, String clientId) {
        var existing = repository.findByExternalId(command.externalId());
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), command, clientId);
        }

        var request = ActivationRequest.create(command.externalId(), command.customerId(), command.productCode(),
                clientId, MDC.get("correlationId"), now());
        try {
            var saved = repository.saveAndFlush(request);
            meterRegistry.counter("activation.requests.created").increment();
            log.info("Solicitação criada id={} externalId={}", saved.getId(), saved.getExternalId());
            return new CreateOutcome.Created(saved);
        } catch (DataIntegrityViolationException e) {
            // Outra requisição com o mesmo externalId gravou entre a nossa busca e o INSERT.
            var winner = repository.findByExternalId(command.externalId()).orElseThrow(() -> e);
            return resolveExisting(winner, command, clientId);
        }
    }

    @Transactional(readOnly = true)
    public ActivationRequest findById(UUID id, String clientId) {
        return repository.findById(id)
                // Registro de outro consumidor responde 404, igual a inexistente, para não revelar que existe.
                .filter(request -> request.isOwnedBy(clientId))
                .orElseThrow(() -> new ActivationRequestNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<ActivationRequest> list(RequestStatus status, String customerId, String clientId, Pageable pageable) {
        Specification<ActivationRequest> spec = (root, query, cb) -> cb.equal(root.get("clientId"), clientId);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (customerId != null && !customerId.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        return repository.findAll(spec, pageable);
    }

    private CreateOutcome resolveExisting(ActivationRequest existing, CreateActivationCommand command, String clientId) {
        if (existing.isOwnedBy(clientId) && existing.hasSameContent(command.customerId(), command.productCode())) {
            log.info("Retry reconhecido para externalId={}, devolvendo id={}", command.externalId(), existing.getId());
            return new CreateOutcome.AlreadyExists(existing);
        }
        log.warn("externalId={} já usado com conteúdo diferente", command.externalId());
        throw new DuplicateExternalIdException(command.externalId());
    }

    private Instant now() {
        // PostgreSQL guarda microssegundos: truncar evita diferença entre o valor em memória e o lido do banco.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
