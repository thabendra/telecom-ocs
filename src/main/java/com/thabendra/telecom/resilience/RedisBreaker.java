package com.thabendra.telecom.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * CB-1: the one breaker in front of every Redis call of this backend copy. Without it a slow Redis costs
 * each request up to the 500 ms timeout per call, several times per request, which is far more than a
 * gateway's 200 ms limit. With it, the calls stop as soon as Redis is clearly in trouble, and each caller
 * goes straight to its fallback.
 * <p>
 * Counted as failures: connection errors and timeouts. An error answer (for example NOPERM) means Redis
 * is alive, so it counts as a success: opening the breaker would not help, and it is a bug to fix.
 * Slow answers count separately (slowCall), because a Redis that answers late hurts as much as none.
 */
@Component
public class RedisBreaker {

    public static final String NAME = "redis";

    private final CircuitBreaker breaker;
    private final Circuits circuits;

    public RedisBreaker(Circuits circuits, CircuitProperties properties) {
        this.circuits = circuits;
        CircuitProperties.Redis redis = properties.redis();
        this.breaker = circuits.create(NAME, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(redis.windowCalls())
                .minimumNumberOfCalls(redis.minimumCalls())
                .failureRateThreshold(redis.failurePercent())
                .slowCallDurationThreshold(redis.slowCall())
                .slowCallRateThreshold(redis.slowPercent())
                .waitDurationInOpenState(redis.openFor())
                .permittedNumberOfCallsInHalfOpenState(redis.testCalls())
                .build());
    }

    /** Runs one Redis call. Throws {@link RedisCircuitOpenException} (a DataAccessException) when open. */
    public <T> T call(Supplier<T> redisCall) {
        if (!breaker.tryAcquirePermission()) {
            circuits.rejected(NAME);
            throw new RedisCircuitOpenException();
        }
        long began = System.nanoTime();
        try {
            T result = redisCall.get();
            breaker.onSuccess(System.nanoTime() - began, TimeUnit.NANOSECONDS);
            return result;
        } catch (RedisConnectionFailureException | QueryTimeoutException outage) {
            breaker.onError(System.nanoTime() - began, TimeUnit.NANOSECONDS, outage);
            throw outage;
        } catch (DataAccessException answered) {
            breaker.onSuccess(System.nanoTime() - began, TimeUnit.NANOSECONDS);   // Redis is alive
            throw answered;
        } catch (RuntimeException other) {
            breaker.releasePermission();   // nothing learned about Redis
            throw other;
        }
    }

    public void run(Runnable redisCall) {
        call(() -> {
            redisCall.run();
            return null;
        });
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    /** Back to closed with empty counters, for tests that stopped Redis on purpose. */
    public void reset() {
        breaker.reset();
    }
}
