package com.deroyque.activation.request.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:03:11Z");

    private ActivationRequest newRequest() {
        return ActivationRequest.create("EXT-1", "CUST-1", "INTERNET-500", "client-a", "corr-1", NOW);
    }

    @Test
    void newRequestStartsPendingAndDueImmediately() {
        var request = newRequest();

        assertThat(request.getId()).isNotNull();
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getAttempts()).isZero();
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(request.getCreatedAt()).isEqualTo(NOW);
        assertThat(request.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void happyPathPendingProcessingCompleted() {
        var request = newRequest();
        var later = NOW.plusSeconds(5);

        request.startProcessing(NOW);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getProcessingStartedAt()).isEqualTo(NOW);

        request.complete(later);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(request.getProcessingStartedAt()).isNull();
        assertThat(request.getUpdatedAt()).isEqualTo(later);
    }

    @Test
    void retryReturnsToPendingAndKeepsAttemptCount() {
        var request = newRequest();
        var retryAt = NOW.plus(Duration.ofSeconds(30));

        request.startProcessing(NOW);
        request.scheduleRetry("HTTP 500", retryAt, NOW);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isEqualTo(retryAt);
        assertThat(request.getLastError()).isEqualTo("HTTP 500");

        request.startProcessing(retryAt);
        assertThat(request.getAttempts()).isEqualTo(2);
    }

    @Test
    void failRecordsReasonTruncated() {
        var request = newRequest();
        request.startProcessing(NOW);

        request.fail("x".repeat(600), NOW);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.FAILED);
        assertThat(request.getLastError()).hasSize(500);
    }

    @Test
    void pendingCannotBeCompletedWithoutProcessing() {
        var request = newRequest();

        assertThatThrownBy(() -> request.complete(NOW))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("PENDING -> COMPLETED");
    }

    @Test
    void completedNeverGoesBackToProcessing() {
        var request = newRequest();
        request.startProcessing(NOW);
        request.complete(NOW);

        assertThatThrownBy(() -> request.startProcessing(NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"COMPLETED", "FAILED"})
    void finalStatesAcceptNoTransition(RequestStatus finalStatus) {
        assertThat(finalStatus.isFinal()).isTrue();
        for (RequestStatus target : RequestStatus.values()) {
            assertThat(finalStatus.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void sameContentComparesBusinessFields() {
        var request = newRequest();

        assertThat(request.hasSameContent("CUST-1", "INTERNET-500")).isTrue();
        assertThat(request.hasSameContent("CUST-1", "TV-100")).isFalse();
        assertThat(request.hasSameContent("CUST-2", "INTERNET-500")).isFalse();
    }
}
