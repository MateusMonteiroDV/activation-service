package com.deroyque.activation.request;

import com.deroyque.activation.provider.ProviderClient;
import com.deroyque.activation.provider.ProviderResult;
import com.deroyque.activation.request.application.ActivationProcessor;
import com.deroyque.activation.request.application.ActivationRequestService;
import com.deroyque.activation.request.application.CreateActivationCommand;
import com.deroyque.activation.request.application.CreateOutcome;
import com.deroyque.activation.request.domain.ActivationRequestRepository;
import com.deroyque.activation.request.domain.RequestStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class ConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ActivationRequestService service;

    @Autowired
    private ActivationRequestRepository repository;

    @Autowired
    private ActivationProcessor processor;

    @MockBean
    private ProviderClient providerClient;

    @MockBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    void concurrentRequestsWithSameExternalIdResultsInExactlyOneDatabaseRecord() throws Exception {
        int threads = 10;
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(threads);
        var executor = Executors.newFixedThreadPool(threads);

        var createdCount = new AtomicInteger();
        var existingCount = new AtomicInteger();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        String externalId = "CONCURRENT-" + System.currentTimeMillis();
        var command = new CreateActivationCommand(externalId, "CUST-999", "INTERNET-1000");

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Aguarda todas as threads estarem prontas para bater juntas
                    var outcome = service.create(command, "client-app");
                    if (outcome instanceof CreateOutcome.Created) {
                        createdCount.incrementAndGet();
                    } else if (outcome instanceof CreateOutcome.AlreadyExists) {
                        existingCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Dispara todas ao mesmo tempo
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(createdCount.get()).isEqualTo(1);
        assertThat(existingCount.get()).isEqualTo(threads - 1);

        // Verificação final no banco de dados real
        assertThat(repository.count()).isEqualTo(1);
        var saved = repository.findByExternalId(externalId).orElseThrow();
        assertThat(saved.getCustomerId()).isEqualTo("CUST-999");
        assertThat(saved.getStatus()).isEqualTo(RequestStatus.PENDING);
    }

    @Test
    void endToEndActivationFlowFromPendingToCompleted() {
        var command = new CreateActivationCommand("EXT-E2E-1", "CUST-1", "INTERNET-500");
        var outcome = service.create(command, "client-app");
        var id = outcome.request().getId();

        when(providerClient.activate(any(), any(), any(), any()))
                .thenReturn(new ProviderResult.Success());

        int processed = processor.processDueRequests();
        assertThat(processed).isEqualTo(1);

        var finished = repository.findById(id).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(finished.getAttempts()).isEqualTo(1);
        assertThat(finished.getProcessingStartedAt()).isNull();
    }
}
