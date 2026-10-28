package com.deroyque.activation.request.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitação de ativação. O status só muda pelos métodos de domínio, que validam a transição.
 */
@Entity
@Table(name = "activation_request")
public class ActivationRequest {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    private UUID id;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;

    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ActivationRequest() {
        // JPA
    }

    public static ActivationRequest create(String externalId, String customerId, String productCode,
                                           String clientId, String correlationId, Instant now) {
        var request = new ActivationRequest();
        request.id = UUID.randomUUID();
        request.externalId = Objects.requireNonNull(externalId);
        request.customerId = Objects.requireNonNull(customerId);
        request.productCode = Objects.requireNonNull(productCode);
        request.clientId = Objects.requireNonNull(clientId);
        request.correlationId = correlationId;
        request.status = RequestStatus.PENDING;
        request.attempts = 0;
        request.nextAttemptAt = now;
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    /** Reservada pelo worker para envio ao provedor. Conta uma tentativa. */
    public void startProcessing(Instant now) {
        transitionTo(RequestStatus.PROCESSING, now);
        attempts++;
        processingStartedAt = now;
        nextAttemptAt = null;
    }

    public void complete(Instant now) {
        transitionTo(RequestStatus.COMPLETED, now);
        processingStartedAt = null;
        lastError = null;
    }

    public void fail(String reason, Instant now) {
        transitionTo(RequestStatus.FAILED, now);
        processingStartedAt = null;
        lastError = truncate(reason);
    }

    /** Falha temporária: volta para a fila e só pode ser processada de novo a partir de {@code retryAt}. */
    public void scheduleRetry(String reason, Instant retryAt, Instant now) {
        transitionTo(RequestStatus.PENDING, now);
        processingStartedAt = null;
        nextAttemptAt = Objects.requireNonNull(retryAt);
        lastError = truncate(reason);
    }

    /** Mesmo conteúdo de negócio: usado para reconhecer um retry legítimo do sistema de origem. */
    public boolean hasSameContent(String customerId, String productCode) {
        return this.customerId.equals(customerId) && this.productCode.equals(productCode);
    }

    public boolean isOwnedBy(String clientId) {
        return this.clientId.equals(clientId);
    }

    private void transitionTo(RequestStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(status, target);
        }
        status = target;
        updatedAt = now;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getClientId() {
        return clientId;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getProcessingStartedAt() {
        return processingStartedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
