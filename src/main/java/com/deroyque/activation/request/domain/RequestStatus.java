package com.deroyque.activation.request.domain;

public enum RequestStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    /**
     * PENDING → PROCESSING; PROCESSING → COMPLETED | FAILED | PENDING (nova tentativa).
     * COMPLETED e FAILED são finais.
     */
    public boolean canTransitionTo(RequestStatus target) {
        return switch (this) {
            case PENDING -> target == PROCESSING;
            case PROCESSING -> target == COMPLETED || target == FAILED || target == PENDING;
            case COMPLETED, FAILED -> false;
        };
    }

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED;
    }
}
