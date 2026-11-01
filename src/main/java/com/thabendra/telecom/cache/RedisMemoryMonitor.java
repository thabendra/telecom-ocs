package com.thabendra.telecom.cache;

import com.thabendra.telecom.monitoring.OcsMetrics;
import com.thabendra.telecom.resilience.RedisBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RD-10: memory and evicted keys of both Redis servers, as metrics for the alerts. Read on a schedule, not
 * when Prometheus scrapes, so a hanging Redis can't hold up /actuator/prometheus. If a check fails the
 * last numbers stay, and the breaker's own alert says why.
 */
@Component
public class RedisMemoryMonitor {

    private static final Logger log = LoggerFactory.getLogger(RedisMemoryMonitor.class);

    private final class Server {
        private final String name;
        private final StringRedisTemplate redis;
        private final RedisBreaker breaker;
        private final AtomicLong used = new AtomicLong();
        private final AtomicLong max = new AtomicLong();
        private final AtomicLong evicted = new AtomicLong();

        private Server(String name, StringRedisTemplate redis, RedisBreaker breaker, OcsMetrics metrics) {
            this.name = name;
            this.redis = redis;
            this.breaker = breaker;
            metrics.redisMemory(name, used::get, max::get, evicted::get);
        }

        private void check() {
            Properties memory = info("memory");
            used.set(number(memory, "used_memory"));
            max.set(number(memory, "maxmemory"));
            evicted.set(number(info("stats"), "evicted_keys"));
        }

        private Properties info(String section) {
            return breaker.call(() -> redis.execute((RedisCallback<Properties>) connection ->
                    connection.serverCommands().info(section)));
        }
    }

    private final List<Server> servers;

    public RedisMemoryMonitor(StringRedisTemplate dataRedis, RedisBreaker dataBreaker, CacheRedis cache,
                              OcsMetrics metrics) {
        this.servers = List.of(new Server("data", dataRedis, dataBreaker, metrics),
                new Server("cache", cache.template(), cache.breaker(), metrics));
    }

    public void check() {
        for (Server server : servers) {
            try {
                server.check();
            } catch (DataAccessException redisDown) {
                log.debug("memory of the {} Redis not read: {}", server.name, redisDown.getMessage());
            }
        }
    }

    private static long number(Properties info, String name) {
        String value = info == null ? null : info.getProperty(name);
        return value == null ? 0 : Long.parseLong(value.trim());
    }

    /** Checks on a schedule, unless telecom.cache.monitor is false. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "telecom.cache.monitor", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final RedisMemoryMonitor monitor;

        Schedule(RedisMemoryMonitor monitor) {
            this.monitor = monitor;
        }

        @Scheduled(fixedDelayString = "${telecom.cache.check-memory-every}")
        void run() {
            monitor.check();
        }
    }
}
