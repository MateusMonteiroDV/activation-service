package com.deroyque.activation.request.application;

/** Mesmo externalId já usado com conteúdo diferente (ou por outro consumidor). */
public class DuplicateExternalIdException extends RuntimeException {

    public DuplicateExternalIdException(String externalId) {
        super("Já existe uma solicitação com externalId '" + externalId + "' e conteúdo diferente");
    }
}
