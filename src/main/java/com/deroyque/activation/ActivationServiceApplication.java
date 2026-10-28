package com.deroyque.activation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ActivationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ActivationServiceApplication.class, args);
    }
}
