package com.deroyque.activation.provider;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
@EnableConfigurationProperties(ProviderProperties.class)
public class HttpProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(HttpProviderClient.class);

    private final RestClient restClient;
    private final Timer providerTimer;
    private final MeterRegistry meterRegistry;

    public HttpProviderClient(ProviderProperties properties, MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.providerTimer = Timer.builder("provider.call.duration")
                .description("Tempo de resposta da chamada ao provedor externo")
                .publishPercentileHistogram()
                .register(meterRegistry);

        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();

        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.url())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public ProviderResult activate(UUID requestId, String externalId, String customerId, String productCode) {
        String correlationId = MDC.get("correlationId");
        var payload = Map.of(
                "externalId", externalId,
                "customerId", customerId,
                "productCode", productCode
        );

        var sample = Timer.start(meterRegistry);
        try {
            log.info("Chamando provedor requestId={} externalId={}", requestId, externalId);
            var response = restClient.post()
                    .uri("/provider/v1/activate")
                    .header("Idempotency-Key", requestId.toString())
                    .headers(h -> {
                        if (correlationId != null) {
                            h.set("X-Correlation-Id", correlationId);
                        }
                    })
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            meterRegistry.counter("provider.call.result", "status", "success").increment();
            return new ProviderResult.Success();
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            String body = ex.getResponseBodyAsString();
            log.warn("Provedor respondeu HTTP {} requestId={} body={}", code, requestId, body);

            if (code == 400 || code == 422) {
                meterRegistry.counter("provider.call.result", "status", "permanent_failure").increment();
                return new ProviderResult.PermanentFailure(code, "HTTP " + code + ": " + body);
            }

            Duration retryAfter = parseRetryAfter(ex.getResponseHeaders());
            meterRegistry.counter("provider.call.result", "status", "retryable_failure").increment();
            return new ProviderResult.RetryableFailure(code, "HTTP " + code + ": " + body, retryAfter);
        } catch (ResourceAccessException ex) {
            log.warn("Timeout ou erro de rede ao chamar o provedor requestId={}: {}", requestId, ex.getMessage());
            meterRegistry.counter("provider.call.result", "status", "timeout").increment();
            return new ProviderResult.RetryableFailure(0, "Timeout/erro de conexão: " + ex.getMessage(), null);
        } catch (Exception ex) {
            log.error("Erro inesperado ao chamar provedor requestId={}", requestId, ex);
            meterRegistry.counter("provider.call.result", "status", "error").increment();
            return new ProviderResult.RetryableFailure(500, "Erro inesperado: " + ex.getMessage(), null);
        } finally {
            sample.stop(providerTimer);
        }
    }

    private Duration parseRetryAfter(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        String header = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return Duration.ofSeconds(Math.max(1, seconds));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
