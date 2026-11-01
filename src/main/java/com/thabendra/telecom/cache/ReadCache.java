package com.thabendra.telecom.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thabendra.telecom.monitoring.OcsMetrics;
import com.thabendra.telecom.resilience.RedisBreaker;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Cache-aside for what customers read often (CA-1): read Redis; on a miss read Oracle and store the result.
 * Oracle stays the truth: nothing here decides a charge, and charging never reads this cache.
 * <ul>
 *   <li>CA-2: a change deletes the key after the commit, and once more a moment later. Deleting, not
 *       updating, can't store an old value; the second delete catches a read that loaded just before the
 *       commit and stored just after the first delete</li>
 *   <li>CA-3: each value lives ttl +/- jitter, so keys stored together don't all expire together</li>
 *   <li>CA-4: when a key is missing one request reloads it, under a short lock; the others wait a moment
 *       and then read Oracle themselves, without storing</li>
 *   <li>CA-5: Redis down or its breaker open: straight to Oracle, behind the database bulkhead as always</li>
 * </ul>
 * If a delete can't reach Redis, the old value lives until its TTL: that is the most a customer can see an
 * old price for.
 */
@Component
public class ReadCache {

    private static final Logger log = LoggerFactory.getLogger(ReadCache.class);
    private static final String LOCK_PREFIX = "cache:lock:";
    private static final long POLL_MS = 20;
    private static final RedisScript<Long> UNLOCK = RedisScript.of(new ClassPathResource("redis/cache-unlock.lua"),
            Long.class);

    private final StringRedisTemplate redis;
    private final RedisBreaker breaker;
    private final CacheProperties properties;
    private final ObjectMapper json;
    private final OcsMetrics metrics;
    private final ScheduledExecutorService delayedDeletes = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "cache-delayed-delete");
        thread.setDaemon(true);
        return thread;
    });

    public ReadCache(CacheRedis cache, CacheProperties properties, ObjectMapper json, OcsMetrics metrics) {
        this.redis = cache.template();
        this.breaker = cache.breaker();
        this.properties = properties;
        this.json = json;
        this.metrics = metrics;
    }

    /** @param name the kind of value, a small fixed set: a metric tag */
    public <T> T get(String name, String key, Class<T> type, Supplier<T> load) {
        return get(name, key, json.constructType(type), load);
    }

    public <T> T get(String name, String key, TypeReference<T> type, Supplier<T> load) {
        return get(name, key, json.constructType(type), load);
    }

    private <T> T get(String name, String key, JavaType type, Supplier<T> load) {
        String token = UUID.randomUUID().toString();
        boolean mine;
        try {
            T cached = read(key, type);
            if (cached != null) {
                metrics.cacheRead(name, "hit");
                return cached;
            }
            mine = Boolean.TRUE.equals(breaker.call(() -> redis.opsForValue()
                    .setIfAbsent(LOCK_PREFIX + key, token, properties.lockTtl())));
        } catch (DataAccessException redisDown) {
            metrics.cacheRead(name, "redis-down");
            return load.get();   // outside the try: an Oracle error isn't a Redis outage
        }
        if (mine) {
            metrics.cacheRead(name, "miss");
            return loadAndStore(key, token, load);
        }
        return waitForTheLoad(name, key, type, load);
    }

    private <T> T loadAndStore(String key, String token, Supplier<T> load) {
        try {
            T value = load.get();
            store(key, value);
            return value;
        } finally {
            unlock(key, token);
        }
    }

    private <T> T waitForTheLoad(String name, String key, JavaType type, Supplier<T> load) {
        long deadline = System.nanoTime() + properties.waitForLoad().toNanos();
        try {
            while (System.nanoTime() < deadline) {
                Thread.sleep(POLL_MS);
                T cached = read(key, type);
                if (cached != null) {
                    metrics.cacheRead(name, "waited");
                    return cached;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (DataAccessException redisDown) {
            log.debug("cache gone while waiting for {}: {}", key, redisDown.getMessage());
        }
        metrics.cacheRead(name, "wait-gave-up");
        return load.get();
    }

    /** Deletes the keys after this transaction commits, nothing if it rolls back; at once without one. */
    public void evictAfterCommit(String... keys) {
        List<String> list = List.of(keys);
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            evict(list);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(list);
            }
        });
    }

    private void evict(List<String> keys) {
        delete(keys);
        delayedDeletes.schedule(() -> delete(keys), properties.delayedDelete().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void delete(List<String> keys) {
        try {
            breaker.run(() -> redis.delete(keys));
        } catch (DataAccessException redisDown) {
            log.warn("cache keys {} not deleted, they live until their TTL: {}", keys, redisDown.getMessage());
        }
    }

    // a value that can't be read (a bug, or an old shape) counts as a miss, and the reload replaces it
    private <T> T read(String key, JavaType type) {
        String value = breaker.call(() -> redis.opsForValue().get(key));
        if (value == null) {
            return null;
        }
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            log.warn("cache key {} can't be read, loading it again: {}", key, e.getOriginalMessage());
            return null;
        }
    }

    private void store(String key, Object value) {
        try {
            String text = json.writeValueAsString(value);
            Duration ttl = withJitter(properties.ttl(), properties.jitterPercent());
            breaker.run(() -> redis.opsForValue().set(key, text, ttl));
        } catch (JsonProcessingException e) {
            log.warn("cache key {} not stored: {}", key, e.getOriginalMessage());
        } catch (DataAccessException redisDown) {
            log.debug("cache key {} not stored: {}", key, redisDown.getMessage());
        }
    }

    private void unlock(String key, String token) {
        try {
            breaker.run(() -> redis.execute(UNLOCK, List.of(LOCK_PREFIX + key), token));
        } catch (DataAccessException redisDown) {
            log.debug("reload lock of {} left to expire: {}", key, redisDown.getMessage());
        }
    }

    /** CA-3: 10 minutes +/- 20 % is anywhere from 8 to 12 minutes. */
    static Duration withJitter(Duration ttl, int jitterPercent) {
        double factor = 1 + ThreadLocalRandom.current().nextDouble(-jitterPercent, jitterPercent + 0.000001) / 100;
        return Duration.ofMillis(Math.max(1, Math.round(ttl.toMillis() * factor)));
    }

    @PreDestroy
    void stop() {
        delayedDeletes.shutdown();
    }
}
