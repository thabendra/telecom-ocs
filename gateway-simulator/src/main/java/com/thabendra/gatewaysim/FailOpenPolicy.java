package com.thabendra.gatewaysim;

import java.time.Duration;

/**
 * simulator.fail-open.* — the failure contract with the backend (RL-6): how far a gateway may let a user's
 * traffic through while the backend can't answer.
 *
 * @param maxDuration how long fail open lasts for one user, from its first moment
 * @param maxMb       how much data one user may use meanwhile
 * @param checkEvery  how often the gateway tries the backend again
 * @param mbPerCheck  simulation: what the user uses between two checks
 */
public record FailOpenPolicy(Duration maxDuration, long maxMb, Duration checkEvery, long mbPerCheck) {

    public static FailOpenPolicy defaults() {
        return new FailOpenPolicy(Duration.ofMinutes(5), 50, Duration.ofSeconds(2), 5);
    }
}
