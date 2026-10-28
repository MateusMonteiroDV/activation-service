package com.deroyque.activation.provider;

import java.time.Duration;

public sealed interface ProviderResult {

    record Success() implements ProviderResult {
    }

    record PermanentFailure(int statusCode, String message) implements ProviderResult {
    }

    record RetryableFailure(int statusCode, String message, Duration retryAfter) implements ProviderResult {
    }
}
