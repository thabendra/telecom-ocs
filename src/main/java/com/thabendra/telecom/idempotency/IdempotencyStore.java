package com.thabendra.telecom.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Redis side of idempotency (RD-1, RD-2, RD-8). Keys look like {@code idem:{clientId}:{key}}
 * and every key is written with a TTL, so nothing stays forever.
 * Only short lived request state lives here; balances and charges stay in Oracle (RD-5).
 */
@Component
public class IdempotencyStore {

    // Lua false comes back as null, which means "miss: lock taken"
    private static final RedisScript<String> CLAIM_SCRIPT =
            RedisScript.of(new ClassPathResource("redis/idempotency-claim.lua"), String.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final IdempotencyProperties properties;

    public IdempotencyStore(StringRedisTemplate redis, ObjectMapper json, IdempotencyProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    static String redisKey(String clientId, String idempotencyKey) {
        return "idem:" + clientId + ":" + idempotencyKey;
    }

    /**
     * One Redis call (redis/idempotency-claim.lua): returns the existing record when the key is
     * already there (hit), otherwise stores the IN_PROGRESS lock with lockTtl and returns empty (RD-2).
     * The script runs atomically, so two copies of a retry can never both get the lock.
     */
    public Optional<IdempotencyRecord> claimOrFind(String redisKey, String requestHash) {
        String existing = redis.execute(CLAIM_SCRIPT, List.of(redisKey),
                write(IdempotencyRecord.inProgress(requestHash)), String.valueOf(properties.lockTtl().toMillis()));
        return Optional.ofNullable(existing).map(this::read);
    }

    /** Replaces the lock with the final response, kept for resultTtl. */
    public void saveResult(String redisKey, IdempotencyRecord result) {
        redis.opsForValue().set(redisKey, write(result), properties.resultTtl());
    }

    /** Drops the key so the caller may retry (used after a 5xx or a crash, ID-9). */
    public void release(String redisKey) {
        redis.delete(redisKey);
    }

    private String write(IdempotencyRecord record) {
        try {
            return json.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private IdempotencyRecord read(String value) {
        try {
            return json.readValue(value, IdempotencyRecord.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable idempotency record", e);
        }
    }
}
