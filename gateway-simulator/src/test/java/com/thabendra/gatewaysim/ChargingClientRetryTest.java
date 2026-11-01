package com.thabendra.gatewaysim;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Section I retry policy (RetryingChargingClient around the REST client) against a mocked backend.
 * Waits are recorded instead of slept, and they move a fake clock forward, so the time budget (RT-8)
 * is checked without real waiting.
 */
class ChargingClientRetryTest {

    private static final String SESSION = "9070f899-b1c9-4840-ab19-ca9d471eb017";
    private static final String START = "http://backend/charging/v1/sessions";
    private static final String ALLOW = """
            {"sessionId":"%s","result":"ALLOW","grantedMb":100,"bandwidthKbps":2048,
             "source":"PLAN","validitySeconds":3600}""".formatted(SESSION);

    private final RetryPolicy policy = RetryPolicy.defaults();
    private final FakeClock clock = new FakeClock();
    private final List<Duration> waits = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    private MockRestServiceServer backend;
    private RetryingCaller caller;
    private ChargingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        backend = MockRestServiceServer.bindTo(builder).build();
        caller = new RetryingCaller(policy, wait -> {
            waits.add(wait);
            clock.advance(wait);
        }, clock, metrics);
        client = new RetryingChargingClient(new RestChargingClient(builder, "http://backend", "pgw-test-01"), caller);
    }

    @Test
    void stillProcessingWaitsRetryAfterAndReusesTheKey() {   // RT-4, RT-6, RT-7
        expect(conflict(true));
        expect(withSuccess(ALLOW, MediaType.APPLICATION_JSON));

        assertThat(start().body().allowed()).isTrue();

        backend.verify();
        assertThat(keys).containsExactly("key-1", "key-1");
        assertThat(waits).containsExactly(Duration.ofSeconds(1));   // Retry-After beats the 0..100 ms jitter
        assertThat(count(RetryingCaller.RETRIES)).isEqualTo(1);
        assertThat(count(RetryingCaller.SUCCEEDED_AFTER_RETRY)).isEqualTo(1);
    }

    @Test
    void gatewayErrorsBackOffExponentiallyWithFullJitter() {   // RT-2, RT-3, RT-4
        expect(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expect(withStatus(HttpStatus.BAD_GATEWAY));
        expect(withStatus(HttpStatus.GATEWAY_TIMEOUT));
        expect(withSuccess(ALLOW, MediaType.APPLICATION_JSON));

        assertThat(start().body().allowed()).isTrue();

        backend.verify();
        assertThat(waits).hasSize(3);
        assertThat(waits.get(0)).isBetween(Duration.ZERO, Duration.ofMillis(100));
        assertThat(waits.get(1)).isBetween(Duration.ZERO, Duration.ofMillis(200));
        assertThat(waits.get(2)).isBetween(Duration.ZERO, Duration.ofMillis(400));
    }

    @Test
    void backoffDoublesUpToTheCap() {   // RT-2
        assertThat(policy.backoff(0)).isEqualTo(Duration.ofMillis(100));
        assertThat(policy.backoff(1)).isEqualTo(Duration.ofMillis(200));
        assertThat(policy.backoff(3)).isEqualTo(Duration.ofMillis(800));
        assertThat(policy.backoff(4)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.backoff(40)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void timeoutIsRetried() {   // RT-4, RT-8 per-try timeout shows up as a SocketTimeoutException
        expect(withException(new SocketTimeoutException("Read timed out")));
        expect(withSuccess(ALLOW, MediaType.APPLICATION_JSON));

        assertThat(start().body().allowed()).isTrue();

        backend.verify();
        assertThat(waits).hasSize(1);
    }

    @Test
    void tooManyRequestsIsRetriedAfterRetryAfter() {   // RT-4, RT-6
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "1");
        expect(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(headers));
        expect(withSuccess(ALLOW, MediaType.APPLICATION_JSON));

        assertThat(start().body().allowed()).isTrue();

        assertThat(waits).containsExactly(Duration.ofSeconds(1));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422, 500})
    void otherErrorsAreFinal(int status) {   // RT-5 (and 500 isn't in the RT-4 list)
        expect(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(this::start)
                .isInstanceOfSatisfying(BackendCallException.class, e -> assertThat(e.status()).isEqualTo(status));

        backend.verify();
        assertThat(waits).isEmpty();
    }

    @Test
    void conflictWithoutRetryAfterIsFinal() {   // Oracle's "already processed": retrying never helps
        expect(conflict(false));

        assertThatThrownBy(this::start).isInstanceOf(BackendCallException.class);

        backend.verify();
        assertThat(waits).isEmpty();
    }

    @Test
    void givesUpAfterThreeRetriesAndFailsOpen() {   // RT-1
        for (int i = 0; i <= policy.maxRetries(); i++) {
            expect(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        assertThatThrownBy(this::start)
                .isInstanceOf(FailOpenException.class)
                .hasMessageContaining("4 tries");

        backend.verify();
        assertThat(keys).hasSize(4);
        assertThat(count(RetryingCaller.RETRIES)).isEqualTo(3);
        assertThat(count(RetryingCaller.EXHAUSTED)).isEqualTo(1);
    }

    @Test
    void stopsWhenTheNextWaitWouldPassTheTimeBudget() {   // RT-8: 1 s + 1 s > 1.5 s
        expect(conflict(true));
        expect(conflict(true));

        assertThatThrownBy(this::start)
                .isInstanceOf(FailOpenException.class)
                .hasMessageContaining("Time budget");

        backend.verify();
        assertThat(waits).containsExactly(Duration.ofSeconds(1));
        assertThat(count(RetryingCaller.EXHAUSTED)).isEqualTo(1);
    }

    @Test
    void aBackendSheddingLoadDoesNotOpenTheCircuit() {   // RL-5: updates and releases must still get through
        for (int i = 0; i < 6; i++) {   // 3 calls of 2 tries: try, wait 1 s, try, and the next wait is over budget
            expect(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1"));
        }

        for (int call = 0; call < 3; call++) {
            assertThatThrownBy(this::start).isInstanceOf(FailOpenException.class)
                    .hasMessageContaining("Time budget");
            clock.advance(Duration.ofSeconds(2));
        }

        backend.verify();
        assertThat(caller.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void circuitOpensAfterFiveFailuresInARowAndThenSkipsTheBackend() {   // RT-9
        // call 1: 4 failed tries; call 2: the 5th failure opens the circuit before its retry
        for (int i = 0; i < policy.failuresToOpen(); i++) {
            expect(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }
        assertThatThrownBy(this::start).isInstanceOf(FailOpenException.class);
        assertThatThrownBy(this::start).isInstanceOf(FailOpenException.class).hasMessageContaining("Circuit open");

        // call 3: no request reaches the backend at all
        assertThatThrownBy(this::start).isInstanceOf(FailOpenException.class).hasMessageContaining("Circuit open");

        backend.verify();
        assertThat(keys).hasSize(policy.failuresToOpen());
        assertThat(caller.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(count(RetryingCaller.CIRCUIT_REJECTED)).isEqualTo(2);
    }

    @Test
    void clientErrorsDoNotOpenTheCircuit() {   // a 4xx is a healthy backend answering
        int calls = policy.failuresToOpen() + 1;
        for (int i = 0; i < calls; i++) {   // all expectations first: the mock refuses new ones after a call
            expect(withStatus(HttpStatus.NOT_FOUND));
        }
        for (int i = 0; i < calls; i++) {
            assertThatThrownBy(this::start).isInstanceOf(BackendCallException.class);
        }

        assertThat(caller.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private ChargingClient.Answer<ChargingClient.Grant> start() {
        return client.start("key-1", "94771234567", "YOUTUBE", 100);
    }

    private void expect(ResponseCreator response) {
        backend.expect(requestTo(START))
                .andExpect(request -> keys.add(request.getHeaders().getFirst("Idempotency-Key")))
                .andRespond(response);
    }

    private static ResponseCreator conflict(boolean withRetryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (withRetryAfter) {
            headers.add(HttpHeaders.RETRY_AFTER, "1");
        }
        return withStatus(HttpStatus.CONFLICT).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"status\":409}");
    }

    private long count(String counter) {
        return (long) metrics.counter(counter).count();
    }

    /** A clock that only moves when a wait is recorded. */
    private static final class FakeClock extends Clock {

        private Instant now = Instant.parse("2026-10-31T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
