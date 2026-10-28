package com.deroyque.activation.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class HttpProviderClientTest {

    private static WireMockServer wireMockServer;
    private HttpProviderClient client;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
        var props = new ProviderProperties(
                wireMockServer.baseUrl(),
                Duration.ofMillis(300),
                Duration.ofMillis(500),
                3,
                Duration.ofMillis(100),
                2.0,
                Duration.ofMillis(500),
                10,
                Duration.ofMinutes(1)
        );
        client = new HttpProviderClient(props, new SimpleMeterRegistry());
    }

    @Test
    void success2xxSendsIdempotencyKey() {
        var requestId = UUID.randomUUID();
        wireMockServer.stubFor(post("/provider/v1/activate")
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ACTIVATED\"}")));

        var result = client.activate(requestId, "EXT-1", "CUST-1", "PROD-1");

        assertThat(result).isInstanceOf(ProviderResult.Success.class);
        wireMockServer.verify(postRequestedFor(urlEqualTo("/provider/v1/activate"))
                .withHeader("Idempotency-Key", equalTo(requestId.toString())));
    }

    @Test
    void badRequest400ReturnsPermanentFailure() {
        var requestId = UUID.randomUUID();
        wireMockServer.stubFor(post("/provider/v1/activate")
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"invalid product code\"}")));

        var result = client.activate(requestId, "EXT-1", "CUST-1", "PROD-1");

        assertThat(result).isInstanceOf(ProviderResult.PermanentFailure.class);
        var failure = (ProviderResult.PermanentFailure) result;
        assertThat(failure.statusCode()).isEqualTo(400);
        assertThat(failure.message()).contains("invalid product code");
    }

    @Test
    void serverError500ReturnsRetryableFailure() {
        var requestId = UUID.randomUUID();
        wireMockServer.stubFor(post("/provider/v1/activate")
                .willReturn(aResponse().withStatus(500).withBody("Internal Error")));

        var result = client.activate(requestId, "EXT-1", "CUST-1", "PROD-1");

        assertThat(result).isInstanceOf(ProviderResult.RetryableFailure.class);
        var failure = (ProviderResult.RetryableFailure) result;
        assertThat(failure.statusCode()).isEqualTo(500);
    }

    @Test
    void rateLimit429WithRetryAfterParsesDuration() {
        var requestId = UUID.randomUUID();
        wireMockServer.stubFor(post("/provider/v1/activate")
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "5")
                        .withBody("Too Many Requests")));

        var result = client.activate(requestId, "EXT-1", "CUST-1", "PROD-1");

        assertThat(result).isInstanceOf(ProviderResult.RetryableFailure.class);
        var failure = (ProviderResult.RetryableFailure) result;
        assertThat(failure.statusCode()).isEqualTo(429);
        assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void readTimeoutReturnsRetryableFailure() {
        var requestId = UUID.randomUUID();
        // Read timeout configurado é 500ms; atraso de 1500ms força o timeout
        wireMockServer.stubFor(post("/provider/v1/activate")
                .willReturn(aResponse()
                        .withFixedDelay(1500)
                        .withStatus(200)));

        var result = client.activate(requestId, "EXT-1", "CUST-1", "PROD-1");

        assertThat(result).isInstanceOf(ProviderResult.RetryableFailure.class);
        var failure = (ProviderResult.RetryableFailure) result;
        assertThat(failure.message()).containsIgnoringCase("timeout");
    }
}
