package com.thabendra.telecom.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * One token bucket per gateway: shared in Redis (RL-1, RD-3), or, while Redis can't be reached, a local
 * bucket with this copy's share of the limit (RD-6). Rate limiting never blocks traffic just because
 * Redis is down; Redis only helps count.
 */
@Component
public class GatewayRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(GatewayRateLimiter.class);

    private final RedisRateLimiter shared;
    private final LocalRateLimiter local;
    private final RateLimitProperties properties;

    public GatewayRateLimiter(RedisRateLimiter shared, RateLimitProperties properties) {
        this(shared, new LocalRateLimiter(Clock.systemUTC()), properties);
    }

    GatewayRateLimiter(RedisRateLimiter shared, LocalRateLimiter local, RateLimitProperties properties) {
        this.shared = shared;
        this.local = local;
        this.properties = properties;
    }

    public Decision tryAcquire(String gateway) {
        RateLimitProperties.Limit limit = properties.limitFor(gateway);
        try {
            return shared.tryAcquire(gateway, limit);
        } catch (DataAccessException redisDown) {
            log.warn("Redis unavailable, rate limiting {} locally at 1/{} of its limit: {}",
                    gateway, properties.instances(), redisDown.getMessage());
            return local.tryAcquire(gateway, limit.share(properties.instances()));
        }
    }
}
