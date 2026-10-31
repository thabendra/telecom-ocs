package com.thabendra.telecom.ratelimit;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The same token bucket as redis/token-bucket.lua, but in this backend copy's memory. Used only while
 * Redis can't be reached (RD-6): each copy then counts on its own with its share of the limit.
 */
public class LocalRateLimiter {

    private static final class Bucket {
        double tokens;
        long updatedAt;

        Bucket(double tokens, long updatedAt) {
            this.tokens = tokens;
            this.updatedAt = updatedAt;
        }
    }

    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LocalRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public Decision tryAcquire(String gateway, RateLimitProperties.Limit limit) {
        long now = clock.millis();
        Bucket bucket = buckets.computeIfAbsent(gateway, name -> new Bucket(limit.capacity(), now));
        synchronized (bucket) {
            // lazy refill, never above capacity
            bucket.tokens = Math.min(limit.capacity(),
                    bucket.tokens + Math.max(0, now - bucket.updatedAt) * limit.refillPerMs());
            bucket.updatedAt = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return Decision.allow();
            }
            return new Decision(false, (long) Math.ceil((1 - bucket.tokens) / limit.refillPerMs()));
        }
    }
}
