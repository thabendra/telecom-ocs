package com.thabendra.telecom.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** telecom.circuit.* in application.yml: the backend's circuit breakers towards what it calls (group 11). */
@ConfigurationProperties("telecom.circuit")
public record CircuitProperties(Redis redis, SigningKeys signingKeys) {

    /**
     * CB-1. Many calls per second, so it goes by a percentage: one failed call among many is just noise.
     *
     * @param windowSeconds  the calls of this many latest seconds count: seconds and not a number of calls,
     *                       as 20 calls can be a few milliseconds under load, and one JVM pause would fill them
     * @param minimumCalls   no decision before this many calls (1 failure of 2 would already be 50 %)
     * @param failurePercent this share of failed calls opens it
     * @param slowCall       a call slower than this counts as slow
     * @param slowPercent    this share of slow calls opens it too: nothing failed, but requests are waiting
     * @param openFor        how long it refuses calls before testing again
     * @param testCalls      calls let through in half-open to decide
     */
    public record Redis(int windowSeconds, int minimumCalls, float failurePercent, Duration slowCall, float slowPercent,
                        Duration openFor, int testCalls) {
    }

    /**
     * CB-2. Fetching keys is rare, so it goes by failures in a row: 20 calls could take days to collect.
     *
     * @param failuresToOpen failures in a row that open it
     * @param openFor        how long it refuses calls before testing again
     * @param timeout        connect and read timeout of one fetch
     * @param refreshEvery   how often the known keys are read again, so a removed key stops being trusted
     * @param unknownKeyGap  least time between fetches caused by a token with an unknown key id, so tokens
     *                       with made-up key ids can't turn into a flood of calls to the token server
     * @param refresh        whether this copy refreshes on a schedule (the tests call it themselves)
     */
    public record SigningKeys(int failuresToOpen, Duration openFor, Duration timeout, Duration refreshEvery,
                              Duration unknownKeyGap, boolean refresh) {
    }
}
