package com.deroyque.activation.request.application;

import com.deroyque.activation.request.domain.ActivationRequest;

/** Resultado da criação: o controller decide 201 (criada agora) ou 200 (retry de uma já existente). */
public sealed interface CreateOutcome {

    ActivationRequest request();

    record Created(ActivationRequest request) implements CreateOutcome {
    }

    record AlreadyExists(ActivationRequest request) implements CreateOutcome {
    }
}
