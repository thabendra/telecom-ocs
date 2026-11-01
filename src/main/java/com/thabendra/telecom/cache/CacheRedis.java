package com.thabendra.telecom.cache;

import com.thabendra.telecom.resilience.CircuitProperties;
import com.thabendra.telecom.resilience.Circuits;
import com.thabendra.telecom.resilience.RedisBreaker;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.SslOptions;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The connection to the cache's own Redis (RD-10), and its own breaker: a cache outage says nothing about
 * the data Redis, and must not send idempotency and rate limiting to their fallbacks too.
 * <p>
 * Not exposed as a RedisConnectionFactory or StringRedisTemplate bean on purpose: Spring Boot's own Redis
 * beans, the ones every data key goes through, are only created when no bean of that type exists.
 */
@Component
public class CacheRedis implements DisposableBean {

    public static final String BREAKER_NAME = "redis-cache";

    private final LettuceConnectionFactory own;
    private final StringRedisTemplate template;
    private final RedisBreaker breaker;

    public CacheRedis(CacheProperties properties, RedisConnectionFactory dataRedis, SslBundles bundles,
                      Circuits circuits, CircuitProperties circuitProperties) {
        CacheProperties.Redis redis = properties.redis();
        this.own = redis.host().isBlank() ? null : connect(redis, bundles);
        this.template = new StringRedisTemplate(own != null ? own : dataRedis);
        this.breaker = new RedisBreaker(BREAKER_NAME, circuits, circuitProperties);
    }

    private static LettuceConnectionFactory connect(CacheProperties.Redis redis, SslBundles bundles) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(redis.host(), redis.port());
        server.setUsername(redis.username());
        server.setPassword(redis.password());
        // the default is 10 s to connect: far too long to wait for something that only saves time
        ClientOptions.Builder options = ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(redis.timeout()).build());
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                .commandTimeout(redis.timeout());
        if (redis.tls()) {
            client.useSsl();
            options.sslOptions(SslOptions.builder().jdkSslProvider()
                    .trustManager(bundles.getBundle("redis-client").getManagers().getTrustManagerFactory())
                    .build());
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(server,
                client.clientOptions(options.build()).build());
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    public StringRedisTemplate template() {
        return template;
    }

    public RedisBreaker breaker() {
        return breaker;
    }

    @Override
    public void destroy() {
        if (own != null) {
            own.destroy();
        }
    }
}
