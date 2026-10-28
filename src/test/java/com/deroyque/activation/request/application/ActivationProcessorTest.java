package com.deroyque.activation.request.application;

import com.deroyque.activation.provider.ProviderClient;
import com.deroyque.activation.provider.ProviderProperties;
import com.deroyque.activation.provider.ProviderResult;
import com.deroyque.activation.request.domain.ActivationRequest;
import com.deroyque.activation.request.domain.ActivationRequestRepository;
import com.deroyque.activation.request.domain.RequestStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationProcessorTest {

    @Mock
    private ActivationRequestRepository repository;

    @Mock
    private ProviderClient providerClient;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final Instant now = Instant.parse("2026-10-26T14:03:11Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private ActivationProcessor processor;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        var properties = new ProviderProperties(
                "http://provider",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                3,
                Duration.ofSeconds(2),
                2.0,
                Duration.ofSeconds(60),
                10,
                Duration.ofMinutes(5)
        );

        processor = new ActivationProcessor(
                repository,
                providerClient,
                properties,
                clock,
                new SimpleMeterRegistry(),
                transactionManager
        );
    }

    @Test
    void processDueRequestsSuccessMarksCompleted() {
        var request = ActivationRequest.create("EXT-1", "CUST-1", "PROD-1", "client-a", "corr-1", now);
        when(repository.lockDueForProcessing(any(), eq(10))).thenReturn(List.of(request));
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        when(providerClient.activate(request.getId(), "EXT-1", "CUST-1", "PROD-1"))
                .thenReturn(new ProviderResult.Success());

        int processed = processor.processDueRequests();

        assertThat(processed).isEqualTo(1);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
    }

    @Test
    void processDueRequestsPermanentFailureMarksFailed() {
        var request = ActivationRequest.create("EXT-1", "CUST-1", "PROD-1", "client-a", "corr-1", now);
        when(repository.lockDueForProcessing(any(), eq(10))).thenReturn(List.of(request));
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        when(providerClient.activate(request.getId(), "EXT-1", "CUST-1", "PROD-1"))
                .thenReturn(new ProviderResult.PermanentFailure(400, "Bad Request"));

        processor.processDueRequests();

        assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(request.getLastError()).contains("Bad Request");
    }

    @Test
    void processDueRequestsRetryableFailureSchedulesRetry() {
        var request = ActivationRequest.create("EXT-1", "CUST-1", "PROD-1", "client-a", "corr-1", now);
        when(repository.lockDueForProcessing(any(), eq(10))).thenReturn(List.of(request));
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        when(providerClient.activate(request.getId(), "EXT-1", "CUST-1", "PROD-1"))
                .thenReturn(new ProviderResult.RetryableFailure(503, "Unavailable", Duration.ofSeconds(10)));

        processor.processDueRequests();

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getNextAttemptAt()).isEqualTo(now.plusSeconds(10));
    }

    @Test
    void processDueRequestsRetryableFailureReachingMaxAttemptsMarksFailed() {
        var request = ActivationRequest.create("EXT-1", "CUST-1", "PROD-1", "client-a", "corr-1", now);
        // Já fez 2 tentativas, claimBatch faz a 3ª que é o máximo configurado (3)
        request.startProcessing(now.minusSeconds(20));
        request.scheduleRetry("err", now.minusSeconds(10), now.minusSeconds(10));
        request.startProcessing(now.minusSeconds(10));
        request.scheduleRetry("err", now, now);

        when(repository.lockDueForProcessing(any(), eq(10))).thenReturn(List.of(request));
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        when(providerClient.activate(request.getId(), "EXT-1", "CUST-1", "PROD-1"))
                .thenReturn(new ProviderResult.RetryableFailure(500, "Server Error", null));

        processor.processDueRequests();

        assertThat(request.getAttempts()).isEqualTo(3);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(request.getLastError()).contains("Limite de 3 tentativas atingido");
    }

    @Test
    void reconcileStuckRequestsRecoversToPending() {
        var request = ActivationRequest.create("EXT-1", "CUST-1", "PROD-1", "client-a", "corr-1", now.minusSeconds(600));
        request.startProcessing(now.minusSeconds(600));

        when(repository.lockStuckInProcessing(any(), eq(10))).thenReturn(List.of(request));
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));

        int recovered = processor.reconcileStuckRequests();

        assertThat(recovered).isEqualTo(1);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getLastError()).contains("Recuperado da reconciliação");
    }
}
