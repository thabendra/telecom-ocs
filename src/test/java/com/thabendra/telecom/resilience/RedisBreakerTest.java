package com.thabendra.telecom.resilience;

import com.thabendra.telecom.TestBreakers;
import com.thabendra.telecom.monitoring.OcsMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CB-1 without Redis: what counts as a failure, how the breaker opens, tests in half-open and closes
 * again. A small window and short waits: of the last 4 calls, 50 % failed or slow (over 40 ms) open it,
 * for 150 ms, and then 2 test calls decide.
 */
class RedisBreakerTest {

    private static final RuntimeException DOWN = new RedisConnectionFailureException("connection refused");

    private SimpleMeterRegistry meters;
    private RedisBreaker breaker;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        CircuitProperties small = new CircuitProperties(
                new CircuitProperties.Redis(4, 4, 50, Duration.ofMillis(40), 50, Duration.ofMillis(150), 2),
                TestBreakers.properties().signingKeys());
        breaker = new RedisBreaker(new Circuits(new OcsMetrics(meters)), small);
    }

    @Test
    void whileClosedCallsGoThrough() {
        assertThat(breaker.call(() -> "answer")).isEqualTo("answer");
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void connectionErrorsOpenItAndThenNothingIsSent() {
        open();
        int sent = calls.get();

        assertThatThrownBy(() -> breaker.call(this::counted)).isInstanceOf(RedisCircuitOpenException.class);

        assertThat(calls.get()).as("no call while open").isEqualTo(sent);
    }

    @Test
    void timeoutsCountAsFailuresToo() {
        for (int i = 0; i < 4; i++) {
            failWith(new QueryTimeoutException("Redis command timed out"));
        }

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void onlyHalfFailedIsEnough() {   // 2 of the last 4
        breaker.call(this::counted);
        failWith(DOWN);
        breaker.call(this::counted);
        failWith(DOWN);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void noDecisionBeforeTheMinimumNumberOfCalls() {
        for (int i = 0; i < 3; i++) {
            failWith(DOWN);
        }

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void anErrorAnswerMeansRedisIsAlive() {   // e.g. NOPERM: a bug to fix, and opening wouldn't help
        for (int i = 0; i < 4; i++) {
            failWith(new RedisSystemException("NOPERM", null));
        }

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aSlowRedisOpensItEvenThoughNothingFailed() {
        for (int i = 0; i < 4; i++) {
            breaker.call(() -> {
                sleep(60);
                return "late";
            });
        }

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void afterTheWaitTestCallsThatWorkCloseIt() {
        open();
        sleep(200);

        breaker.call(this::counted);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        breaker.call(this::counted);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void aFailedTestOpensItAgain() {
        open();
        sleep(200);

        failWith(DOWN);
        failWith(DOWN);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void theFallbacksKeepWorkingBecauseItIsADataAccessException() {
        open();

        assertThatThrownBy(() -> breaker.run(() -> { })).isInstanceOf(DataAccessException.class);
    }

    @Test
    void anErrorThatIsNotAboutRedisTellsItNothing() {
        for (int i = 0; i < 8; i++) {
            assertThatThrownBy(() -> breaker.call(() -> {
                throw new IllegalStateException("our own bug");
            })).isInstanceOf(IllegalStateException.class);
        }

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void theStateAndRefusedCallsAreMetrics() {
        assertThat(gauge("closed")).isEqualTo(1);
        assertThat(gauge("open")).isZero();

        open();
        assertThatThrownBy(() -> breaker.call(this::counted)).isInstanceOf(RedisCircuitOpenException.class);

        assertThat(gauge("open")).isEqualTo(1);
        assertThat(gauge("closed")).isZero();
        assertThat(meters.get("ocs.circuit.rejected").tag("name", RedisBreaker.NAME).counter().count()).isEqualTo(1);
    }

    private void open() {
        for (int i = 0; i < 4; i++) {
            failWith(DOWN);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private void failWith(RuntimeException error) {
        Supplier<String> failing = () -> {
            throw error;
        };
        assertThatThrownBy(() -> breaker.call(failing)).isSameAs(error);
    }

    private String counted() {
        calls.incrementAndGet();
        return "answer";
    }

    private double gauge(String state) {
        return meters.get("ocs.circuit.state").tag("name", RedisBreaker.NAME).tag("state", state).gauge().value();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
