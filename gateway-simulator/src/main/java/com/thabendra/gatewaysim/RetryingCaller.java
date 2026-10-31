package com.thabendra.gatewaysim;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs one backend call with the gateway's retry policy (section I):
 * <ul>
 *   <li>RT-1: at most maxRetries retries, then fail open</li>
 *   <li>RT-2/RT-3: wait random(0, backoff) with backoff 100 ms, 200 ms, 400 ms ... up to 1 s (full jitter)</li>
 *   <li>RT-4/RT-5: retry only timeouts, connection errors, 502/503/504, 429 and 409 still-in-progress;
 *       any other answer is final</li>
 *   <li>RT-6: a Retry-After header is the minimum wait</li>
 *   <li>RT-7: the caller's request (same Idempotency-Key, same body) is simply sent again</li>
 *   <li>RT-8: if the next wait would go past the total time budget, stop and fail open</li>
 *   <li>RT-9: a circuit breaker opens after failuresToOpen failed tries in a row and then fails open
 *       at once, without calling the backend</li>
 *   <li>RT-10: counters for retries, calls that succeeded after a retry, and calls that gave up</li>
 * </ul>
 * Only network errors and 5xx count as backend failures for the breaker; a 4xx is a healthy answer.
 * Errors arrive as {@link BackendCallException}, so the same rules apply to REST and SOAP.
 */
public class RetryingCaller {

    /** How the caller waits between tries; tests pass one that records instead of sleeping. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    static final String RETRIES = "gateway.retries";
    static final String SUCCEEDED_AFTER_RETRY = "gateway.retries.succeeded";
    static final String EXHAUSTED = "gateway.retries.exhausted";
    static final String CIRCUIT_REJECTED = "gateway.circuit.rejected";

    private static final Logger log = LoggerFactory.getLogger(RetryingCaller.class);
    private static final Set<Integer> RETRYABLE_SERVER_ERRORS = Set.of(502, 503, 504);

    private final RetryPolicy policy;
    private final Sleeper sleeper;
    private final Clock clock;
    private final CircuitBreaker breaker;
    private final Counter retries;
    private final Counter succeededAfterRetry;
    private final Counter exhausted;
    private final Counter circuitRejected;

    public RetryingCaller(RetryPolicy policy, Sleeper sleeper, Clock clock, MeterRegistry metrics) {
        this.policy = policy;
        this.sleeper = sleeper;
        this.clock = clock;
        this.breaker = CircuitBreaker.of("charging-backend", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(policy.failuresToOpen())
                .minimumNumberOfCalls(policy.failuresToOpen())
                .failureRateThreshold(100)            // all of the last N tries failed = N failures in a row
                .waitDurationInOpenState(policy.openFor())
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
        this.retries = metrics.counter(RETRIES);
        this.succeededAfterRetry = metrics.counter(SUCCEEDED_AFTER_RETRY);
        this.exhausted = metrics.counter(EXHAUSTED);
        this.circuitRejected = metrics.counter(CIRCUIT_REJECTED);
    }

    public CircuitBreaker.State circuitState() {
        return breaker.getState();
    }

    public <T> T call(Supplier<T> attempt) {
        Instant start = clock.instant();
        for (int retry = 0; ; retry++) {
            if (!breaker.tryAcquirePermission()) {
                circuitRejected.increment();
                throw new FailOpenException("Circuit open: backend failed " + policy.failuresToOpen()
                        + " times in a row, not calling it for " + policy.openFor(), null);
            }
            long began = System.nanoTime();
            try {
                T result = attempt.get();
                breaker.onSuccess(System.nanoTime() - began, TimeUnit.NANOSECONDS);
                if (retry > 0) {
                    succeededAfterRetry.increment();
                }
                return result;
            } catch (RuntimeException e) {
                long took = System.nanoTime() - began;
                if (isBackendFailure(e)) {
                    breaker.onError(took, TimeUnit.NANOSECONDS, e);
                } else {
                    breaker.onSuccess(took, TimeUnit.NANOSECONDS);   // the backend answered
                }
                if (!isRetryable(e)) {
                    throw e;
                }
                if (retry == policy.maxRetries()) {
                    exhausted.increment();
                    throw new FailOpenException("Gave up after " + (retry + 1) + " tries", e);
                }
                Duration wait = waitBefore(retry, e);
                Duration used = Duration.between(start, clock.instant());
                if (used.plus(wait).compareTo(policy.totalBudget()) > 0) {
                    exhausted.increment();
                    throw new FailOpenException("Time budget " + policy.totalBudget() + " used up after "
                            + (retry + 1) + " tries", e);
                }
                retries.increment();
                log.info("       retry {} in {} ms after {}", retry + 1, wait.toMillis(), describe(e));
                sleep(wait);
            }
        }
    }

    // RT-4: no answer (timeout, connection error), 502/503/504, 429, and 409 with Retry-After (still in progress)
    static boolean isRetryable(RuntimeException e) {
        if (!(e instanceof BackendCallException call)) {
            return false;
        }
        int status = call.status();
        return status == BackendCallException.NO_ANSWER
                || RETRYABLE_SERVER_ERRORS.contains(status)
                || status == 429
                || (status == 409 && call.retryAfter() != null);   // a 409 without it is final
    }

    private static boolean isBackendFailure(RuntimeException e) {
        return e instanceof BackendCallException call
                && (call.status() == BackendCallException.NO_ANSWER || call.status() >= 500);
    }

    // RT-2/3/6: full jitter on the backoff, but never shorter than Retry-After
    private Duration waitBefore(int retry, RuntimeException e) {
        Duration jittered = Duration.ofMillis(ThreadLocalRandom.current().nextLong(policy.backoff(retry).toMillis() + 1));
        Duration retryAfter = e instanceof BackendCallException call ? call.retryAfter() : null;
        return retryAfter != null && retryAfter.compareTo(jittered) > 0 ? retryAfter : jittered;
    }

    private static String describe(RuntimeException e) {
        if (e instanceof BackendCallException call) {
            return call.status() == BackendCallException.NO_ANSWER ? "no answer" : "status " + call.status();
        }
        return e.getClass().getSimpleName();
    }

    private void sleep(Duration wait) {
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FailOpenException("Interrupted while waiting to retry", interrupted);
        }
    }
}
