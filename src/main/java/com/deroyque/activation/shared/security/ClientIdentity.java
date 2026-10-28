package com.deroyque.activation.shared.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Identifica o sistema consumidor a partir do token. Em client credentials o "sub" costuma ser o próprio
 * client; alguns provedores usam "client_id" (Spring Authorization Server, Okta) ou "azp" (Keycloak).
 */
public final class ClientIdentity {

    private ClientIdentity() {
    }

    public static String from(Jwt jwt) {
        for (String claim : new String[]{"client_id", "azp", "sub"}) {
            String value = jwt.getClaimAsString(claim);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        throw new IllegalStateException("Token sem identificação do consumidor");
    }
}
