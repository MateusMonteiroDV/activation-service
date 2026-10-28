package com.deroyque.activation.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Relógio injetável: testes controlam o tempo sem depender de Instant.now(). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
