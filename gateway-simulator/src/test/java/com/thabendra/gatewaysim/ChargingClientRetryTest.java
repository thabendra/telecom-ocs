package com.thabendra.gatewaysim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 409 + Retry-After from the backend: wait, then retry with the same Idempotency-Key, at most 3 times. */
class ChargingClientRetryTest {

    private static final String SESSION = "9070f899-b1c9-4840-ab19-ca9d471eb017";
    private static final String START = "http://backend/charging/v1/sessions";
    private static final String ALLOW = """
            {"sessionId":"%s","result":"ALLOW","grantedMb":100,"bandwidthKbps":2048,
             "source":"PLAN","validitySeconds":3600}""".formatted(SESSION);

    private MockRestServiceServer backend;
    private ChargingClient client;
    private final List<String> keys = new ArrayList<>();
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        backend = MockRestServiceServer.bindTo(builder).build();
        client = new ChargingClient(builder, "http://backend", "pgw-test-01", sleeps::add);
    }

    @Test
    void stillProcessingIsRetriedWithSameKeyAfterRetryAfterPlusJitter() {
        expectStillProcessing();
        expectAllow();

        ChargingClient.Answer<ChargingClient.Grant> answer = client.start("key-1", "94771234567", "YOUTUBE", 100);

        backend.verify();
        assertThat(answer.body().allowed()).isTrue();
        assertThat(keys).containsExactly("key-1", "key-1");   // the retry reused the key
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isBetween(Duration.ofSeconds(1),
                Duration.ofSeconds(1).plusMillis(ChargingClient.MAX_JITTER_MS));
    }

    @Test
    void givesUpAfterThreeRetries() {
        for (int i = 0; i <= ChargingClient.MAX_RETRIES; i++) {
            expectStillProcessing();
        }

        assertThatThrownBy(() -> client.start("key-1", "94771234567", "YOUTUBE", 100))
                .isInstanceOf(HttpClientErrorException.Conflict.class);

        backend.verify();
        assertThat(keys).hasSize(1 + ChargingClient.MAX_RETRIES);   // first try + 3 retries
        assertThat(sleeps).hasSize(ChargingClient.MAX_RETRIES);
    }

    @Test
    void conflictWithoutRetryAfterIsFinal() {
        // e.g. Oracle's "request already processed": retrying would never help
        backend.expect(requestTo(START))
                .andExpect(request -> keys.add(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":409,\"detail\":\"already processed\"}"));

        assertThatThrownBy(() -> client.start("key-1", "94771234567", "YOUTUBE", 100))
                .isInstanceOf(HttpClientErrorException.Conflict.class);

        backend.verify();
        assertThat(keys).hasSize(1);
        assertThat(sleeps).isEmpty();
    }

    private void expectStillProcessing() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "1");
        backend.expect(requestTo(START))
                .andExpect(request -> keys.add(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withStatus(HttpStatus.CONFLICT).headers(headers)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"status\":409,\"detail\":\"still being processed\"}"));
    }

    private void expectAllow() {
        backend.expect(requestTo(START))
                .andExpect(request -> keys.add(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(withSuccess(ALLOW, MediaType.APPLICATION_JSON));
    }
}
