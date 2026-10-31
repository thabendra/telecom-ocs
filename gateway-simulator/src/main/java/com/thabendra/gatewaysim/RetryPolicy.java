package com.thabendra.gatewaysim;

import java.time.Duration;

/**
 * simulator.retry.* — how the gateway retries a charging call (section I).
 *
 * @param maxRetries      RT-1: retries after the first try (3 = 4 tries in total)
 * @param baseBackoff     RT-2: backoff before the first retry, doubled each time
 * @param maxBackoff      RT-2: the backoff never grows above this
 * @param perTryTimeout   RT-8: one try gives up after this (connect and read)
 * @param totalBudget     RT-8: all tries and waits together must fit in this
 * @param failuresToOpen  RT-9: this many failed tries in a row open the circuit
 * @param openFor         RT-9: how long the open circuit rejects calls before trying one again
 */
public record RetryPolicy(int maxRetries, Duration baseBackoff, Duration maxBackoff, Duration perTryTimeout,
                          Duration totalBudget, int failuresToOpen, Duration openFor) {

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofMillis(200),
                Duration.ofMillis(1500), 5, Duration.ofSeconds(10));
    }

    /** RT-2: 100 ms, 200 ms, 400 ms, ... capped at maxBackoff; retry 0 is the wait before the first retry. */
    public Duration backoff(int retry) {
        Duration backoff = baseBackoff.multipliedBy(1L << Math.min(retry, 30));
        return backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
    }
}
