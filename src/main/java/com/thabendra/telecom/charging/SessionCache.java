package com.thabendra.telecom.charging;

import com.thabendra.telecom.resilience.RedisBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * RD-4: open sessions in Redis, session:{id} -> "gateway|msisdn", so a call from the wrong gateway
 * (CH-7) is refused before Oracle is touched. Oracle stays the real check: a session that isn't in
 * the cache (Redis was down, the key expired, another copy started it) simply goes on to the PL/SQL,
 * which checks the owner again. So the cache only ever saves work, it never decides alone that a
 * call is allowed. Redis errors are ignored for the same reason.
 */
@Component
public class SessionCache {

    private static final Logger log = LoggerFactory.getLogger(SessionCache.class);

    /** Who owns a session. */
    public record Owner(String gateway, String msisdn) {
    }

    private final StringRedisTemplate redis;
    private final ChargingProperties properties;
    private final RedisBreaker breaker;

    public SessionCache(StringRedisTemplate redis, ChargingProperties properties, RedisBreaker breaker) {
        this.redis = redis;
        this.properties = properties;
        this.breaker = breaker;
    }

    static String key(String sessionId) {
        return "session:" + sessionId;
    }

    /** After a start or an update: kept as long as the session can live without an update (CH-8). */
    public void remember(String sessionId, String gateway, String msisdn) {
        try {
            breaker.run(() -> redis.opsForValue().set(key(sessionId), gateway + "|" + msisdn,
                    properties.sessionExpiry()));
        } catch (DataAccessException redisDown) {
            log.debug("session cache not updated for {}: {}", sessionId, redisDown.getMessage());
        }
    }

    public Optional<Owner> find(String sessionId) {
        try {
            String value = breaker.call(() -> redis.opsForValue().get(key(sessionId)));
            if (value == null || value.indexOf('|') < 0) {
                return Optional.empty();
            }
            int bar = value.indexOf('|');
            return Optional.of(new Owner(value.substring(0, bar), value.substring(bar + 1)));
        } catch (DataAccessException redisDown) {
            return Optional.empty();   // Oracle checks the owner anyway
        }
    }

    public void forget(String sessionId) {
        try {
            breaker.run(() -> redis.delete(key(sessionId)));
        } catch (DataAccessException redisDown) {
            log.debug("session cache entry not removed for {}: {}", sessionId, redisDown.getMessage());
        }
    }
}
