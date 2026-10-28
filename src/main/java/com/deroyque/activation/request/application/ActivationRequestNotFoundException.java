package com.deroyque.activation.request.application;

import java.util.UUID;

public class ActivationRequestNotFoundException extends RuntimeException {

    public ActivationRequestNotFoundException(UUID id) {
        super("Solicitação " + id + " não encontrada");
    }
}
