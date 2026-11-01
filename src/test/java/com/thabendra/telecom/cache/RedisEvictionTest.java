package com.thabendra.telecom.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RD-10: the two policies from docker-compose.yml on real Redis servers, with little memory so they fill
 * quickly: the cache evicts and goes on, the data server refuses a write and loses no key. Skipped when
 * Docker isn't running.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisEvictionTest {

    private static final String KILOBYTE = "x".repeat(1024);
    private static final int KEYS = 10_000;   // 10 MB, in a server limited to 5 MB

    @Container
    static final GenericContainer<?> CACHE = redis("allkeys-lru");

    @Container
    static final GenericContainer<?> DATA = redis("noeviction");

    private final List<LettuceConnectionFactory> factories = new ArrayList<>();

    private static GenericContainer<?> redis(String policy) {
        return new GenericContainer<>("redis:7.4-alpine")
                .withCommand("redis-server", "--maxmemory", "5mb", "--maxmemory-policy", policy)
                .withExposedPorts(6379);
    }

    @AfterEach
    void closeConnections() {
        factories.forEach(LettuceConnectionFactory::destroy);
    }

    @Test
    void theCacheEvictsTheOldestKeysAndEveryWriteWorks() {
        StringRedisTemplate cache = connect(CACHE);

        for (int i = 0; i < KEYS; i++) {
            cache.opsForValue().set("cache:v1:profile:" + i, KILOBYTE);
        }

        assertThat(evictedKeys(cache)).isPositive();
        assertThat(cache.opsForValue().get("cache:v1:profile:" + (KEYS - 1))).isNotNull();
    }

    @Test
    void theDataServerRefusesAWriteWhenFullAndKeepsEveryKey() {
        StringRedisTemplate data = connect(DATA);

        int written = 0;
        assertThatThrownBy(() -> {
            for (int i = 0; i < KEYS; i++) {
                data.opsForValue().set("idem:gw:" + i, KILOBYTE);
            }
        }).isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("OOM");

        while (written < KEYS && Boolean.TRUE.equals(data.hasKey("idem:gw:" + written))) {
            written++;
        }
        assertThat(written).isPositive();
        assertThat(data.opsForValue().get("idem:gw:0")).isEqualTo(KILOBYTE);
        assertThat(evictedKeys(data)).isZero();
    }

    private static long evictedKeys(StringRedisTemplate redis) {
        Properties stats = redis.execute((RedisCallback<Properties>) connection ->
                connection.serverCommands().info("stats"));
        return Long.parseLong(stats.getProperty("evicted_keys").trim());
    }

    private StringRedisTemplate connect(GenericContainer<?> server) {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(server.getHost(), server.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        factories.add(factory);
        return new StringRedisTemplate(factory);
    }
}
