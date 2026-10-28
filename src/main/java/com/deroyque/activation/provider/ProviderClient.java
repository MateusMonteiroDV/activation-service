package com.deroyque.activation.provider;

import java.util.UUID;

public interface ProviderClient {

    ProviderResult activate(UUID requestId, String externalId, String customerId, String productCode);
}
