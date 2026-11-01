package com.thabendra.telecom.ratelimit;

import com.thabendra.telecom.resilience.RedisBreaker;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The shared bucket per gateway in Redis (RL-1), updated by redis/token-bucket.lua (RD-3). Every
 * backend copy uses the same key, so the limit is per gateway in total, however many copies run.
 * Redis errors are thrown as DataAccessException; {@link GatewayRateLimiter} falls back then.
 */
@Component
public class RedisRateLimiter {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET =
            RedisScript.of(new ClassPathResource("redis/token-bucket.lua"), List.class);

    private final StringRedisTemplate redis;
    private final RedisBreaker breaker;

    public RedisRateLimiter(StringRedisTemplate redis, RedisBreaker breaker) {
        this.redis = redis;
        this.breaker = breaker;
    }

    static String redisKey(String gateway) {
        return "ratelimit:" + gateway;
    }

    public Decision tryAcquire(String gateway, RateLimitProperties.Limit limit) {
        List<?> answer = breaker.call(() -> redis.execute(TOKEN_BUCKET, List.of(redisKey(gateway)),
                String.valueOf(limit.capacity()), String.valueOf(limit.refillPerMs())));
        boolean allowed = ((Number) answer.get(0)).longValue() == 1;
        return new Decision(allowed, ((Number) answer.get(1)).longValue());
    }
}
