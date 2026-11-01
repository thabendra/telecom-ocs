package com.thabendra.telecom.ratelimit;

import com.thabendra.telecom.resilience.RedisCircuitOpenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * One token bucket per gateway: shared in Redis (RL-1, RD-3), or, while Redis can't be reached, a local
 * bucket with this copy's share of the limit (RD-6). Rate limiting never blocks traffic just because
 * Redis is down; Redis only helps count. The public API's buckets per user and per IP (PR-3) use the
 * same two-level counting through {@link #tryAcquire(String, RateLimitProperties.Limit)}.
 */
@Component
public class GatewayRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(GatewayRateLimiter.class);

    private final RedisRateLimiter shared;
    private final LocalRateLimiter local;
    private final RateLimitProperties properties;

    // two constructors: tell Spring which one is its own (the other is for tests with a fixed clock)
    @Autowired
    public GatewayRateLimiter(RedisRateLimiter shared, RateLimitProperties properties) {
        this(shared, new LocalRateLimiter(Clock.systemUTC()), properties);
    }

    GatewayRateLimiter(RedisRateLimiter shared, LocalRateLimiter local, RateLimitProperties properties) {
        this.shared = shared;
        this.local = local;
        this.properties = properties;
    }

    public Decision tryAcquire(String gateway) {
        return tryAcquire(gateway, properties.limitFor(gateway));
    }

    /** Any bucket by name: a gateway, or "user:...", "staff:...", "ip:..." on the public API (PR-3). */
    public Decision tryAcquire(String bucket, RateLimitProperties.Limit limit) {
        try {
            return shared.tryAcquire(bucket, limit);
        } catch (DataAccessException redisDown) {
            // while the circuit is open this is every request, and the breaker has said so once already
            if (!(redisDown instanceof RedisCircuitOpenException)) {
                log.warn("Redis unavailable, rate limiting {} locally at 1/{} of its limit: {}",
                        bucket, properties.instances(), redisDown.getMessage());
            }
            return local.tryAcquire(bucket, limit.share(properties.instances()));
        }
    }
}
