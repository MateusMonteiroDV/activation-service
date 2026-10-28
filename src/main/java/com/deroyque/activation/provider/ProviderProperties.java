package com.deroyque.activation.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "provider")
public record ProviderProperties(
        String url,
        Duration connectTimeout,
        Duration readTimeout,
        int maxAttempts,
        Duration initialBackoff,
        double backoffMultiplier,
        Duration maxBackoff,
        int batchSize,
        Duration stuckTimeout
) {
    public ProviderProperties {
        if (url == null || url.isBlank()) {
            url = "http://localhost:8081";
        }
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(2);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(3);
        }
        if (maxAttempts <= 0) {
            maxAttempts = 5;
        }
        if (initialBackoff == null) {
            initialBackoff = Duration.ofSeconds(2);
        }
        if (backoffMultiplier <= 1.0) {
            backoffMultiplier = 2.0;
        }
        if (maxBackoff == null) {
            maxBackoff = Duration.ofMinutes(1);
        }
        if (batchSize <= 0) {
            batchSize = 20;
        }
        if (stuckTimeout == null) {
            stuckTimeout = Duration.ofMinutes(5);
        }
    }
}
