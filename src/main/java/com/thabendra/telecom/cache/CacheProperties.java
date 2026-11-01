package com.thabendra.telecom.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * telecom.cache.* in application.yml.
 *
 * @param redis              the Redis that holds the cache (RD-10), not the one that holds data
 * @param ttl                how long a value lives (CA-3)
 * @param jitterPercent      each value's life is ttl, plus or minus up to this much (CA-3)
 * @param lockTtl            one request reloads an expired key (CA-4); its lock is freed after this if it dies
 * @param waitForLoad        how long the others wait for that reload before they read Oracle themselves
 * @param delayedDelete      the second delete after a change (CA-2)
 * @param monitor            read Redis memory numbers on a schedule, for the alerts (RD-10)
 * @param checkMemoryEvery   how often
 */
@ConfigurationProperties("telecom.cache")
public record CacheProperties(Redis redis, Duration ttl, int jitterPercent, Duration lockTtl, Duration waitForLoad,
                              Duration delayedDelete, boolean monitor, Duration checkMemoryEvery) {

    /** @param host empty = use the data Redis' connection (only for tests: there the two are one container) */
    public record Redis(String host, int port, String username, String password, boolean tls, Duration timeout) {
    }
}
