package com.thabendra.telecom.ratelimit;

import io.lettuce.core.ReadFrom;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

/**
 * RD-7, profile "redis-sentinel": make the Redis client follow a failover.
 * <p>
 * Asking a Sentinel for the primary only once is not enough: when the primary hangs, the open TCP
 * connection to it stays up, every command just times out, and the client never asks again. With a
 * read-from setting, Lettuce runs in master/replica mode instead: it subscribes to the Sentinels'
 * events, and on +switch-master moves to the promoted replica by itself. UPSTREAM keeps every read on
 * the primary too, because idempotency keys and rate limit buckets must be read where they are written.
 */
@Configuration
@Profile("redis-sentinel")
public class RedisSentinelConfig {

    @Bean
    LettuceClientConfigurationBuilderCustomizer followSentinelFailover() {
        return RedisSentinelConfig::followFailover;
    }

    public static void followFailover(LettuceClientConfiguration.LettuceClientConfigurationBuilder builder) {
        builder.readFrom(ReadFrom.UPSTREAM);
    }
}
