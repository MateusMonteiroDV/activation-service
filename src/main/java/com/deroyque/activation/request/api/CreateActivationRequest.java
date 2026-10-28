package com.deroyque.activation.request.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Regras: obrigatórios, até 64 caracteres, apenas letras, números, ponto, hífen e sublinhado.
 */
public record CreateActivationRequest(
        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = ID_PATTERN, message = ID_MESSAGE)
        String externalId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = ID_PATTERN, message = ID_MESSAGE)
        String customerId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = ID_PATTERN, message = ID_MESSAGE)
        String productCode
) {
    static final String ID_PATTERN = "^[A-Za-z0-9._-]*$";
    static final String ID_MESSAGE = "aceita apenas letras, números, '.', '-' e '_'";
}
