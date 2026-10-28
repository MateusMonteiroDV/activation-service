package com.deroyque.activation.request.application;

/** Dados de entrada já validados pela API. */
public record CreateActivationCommand(String externalId, String customerId, String productCode) {
}
