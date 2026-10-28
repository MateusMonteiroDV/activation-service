package com.deroyque.activation.request.application;

import com.deroyque.activation.request.domain.ActivationRequest;
import com.deroyque.activation.request.domain.ActivationRequestRepository;
import com.deroyque.activation.request.domain.RequestStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationRequestServiceTest {

    @Mock
    private ActivationRequestRepository repository;

    private final Instant now = Instant.parse("2026-10-26T14:03:11Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private ActivationRequestService service;

    @BeforeEach
    void setUp() {
        service = new ActivationRequestService(repository, clock, new SimpleMeterRegistry());
    }

    @Test
    void createNewRequestSuccess() {
        var command = new CreateActivationCommand("EXT-100", "CUST-1", "INTERNET-500");
        when(repository.findByExternalId("EXT-100")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(ActivationRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        var outcome = service.create(command, "client-a");

        assertThat(outcome).isInstanceOf(CreateOutcome.Created.class);
        var created = (CreateOutcome.Created) outcome;
        assertThat(created.request().getExternalId()).isEqualTo("EXT-100");
        assertThat(created.request().getClientId()).isEqualTo("client-a");
        assertThat(created.request().getStatus()).isEqualTo(RequestStatus.PENDING);
    }

    @Test
    void createDuplicateWithSameContentReturnsAlreadyExists() {
        var existing = ActivationRequest.create("EXT-100", "CUST-1", "INTERNET-500", "client-a", "corr-1", now);
        when(repository.findByExternalId("EXT-100")).thenReturn(Optional.of(existing));

        var command = new CreateActivationCommand("EXT-100", "CUST-1", "INTERNET-500");
        var outcome = service.create(command, "client-a");

        assertThat(outcome).isInstanceOf(CreateOutcome.AlreadyExists.class);
        assertThat(outcome.request()).isSameAs(existing);
    }

    @Test
    void createDuplicateWithDifferentContentThrowsDuplicateExternalIdException() {
        var existing = ActivationRequest.create("EXT-100", "CUST-1", "INTERNET-500", "client-a", "corr-1", now);
        when(repository.findByExternalId("EXT-100")).thenReturn(Optional.of(existing));

        var command = new CreateActivationCommand("EXT-100", "CUST-1", "TV-100");

        assertThatThrownBy(() -> service.create(command, "client-a"))
                .isInstanceOf(DuplicateExternalIdException.class)
                .hasMessageContaining("EXT-100");
    }

    @Test
    void createConcurrentDuplicateCatchesDataIntegrityViolationAndResolvesExisting() {
        var existing = ActivationRequest.create("EXT-100", "CUST-1", "INTERNET-500", "client-a", "corr-1", now);
        // Na primeira busca não achou...
        when(repository.findByExternalId("EXT-100"))
                .thenReturn(Optional.empty())
                // ...mas no catch da violação de unicidade ele encontra
                .thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("Unique constraint"));

        var command = new CreateActivationCommand("EXT-100", "CUST-1", "INTERNET-500");
        var outcome = service.create(command, "client-a");

        assertThat(outcome).isInstanceOf(CreateOutcome.AlreadyExists.class);
    }

    @Test
    void findByIdSuccess() {
        var id = UUID.randomUUID();
        var existing = ActivationRequest.create("EXT-100", "CUST-1", "INTERNET-500", "client-a", "corr-1", now);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        var result = service.findById(id, "client-a");

        assertThat(result).isSameAs(existing);
    }

    @Test
    void findByIdNotFoundThrowsException() {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id, "client-a"))
                .isInstanceOf(ActivationRequestNotFoundException.class);
    }

    @Test
    void findByIdOwnedByDifferentClientThrowsNotFoundForIsolation() {
        var id = UUID.randomUUID();
        var existing = ActivationRequest.create("EXT-100", "CUST-1", "INTERNET-500", "client-other", "corr-1", now);
        when(repository.findById(id)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.findById(id, "client-a"))
                .isInstanceOf(ActivationRequestNotFoundException.class);
    }
}
