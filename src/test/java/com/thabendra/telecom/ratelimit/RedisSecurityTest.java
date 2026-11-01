package com.thabendra.telecom.ratelimit;

import com.thabendra.telecom.TestBreakers;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RD-9 against a Redis set up exactly like docker-compose.yml: TLS only, with the certificate from
 * scripts/generate-dev-certs.sh, and the users in docker/redis/users.acl. Checks that the backend's
 * user can do its real work (the Lua scripts, the health check) and nothing more, and that nobody
 * gets in without TLS, a user and the right password. Skipped when Docker isn't running.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisSecurityTest {

    private static final String USER = "telecom-ocs";
    private static final String PASSWORD = "dev-only-redis-password";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withCopyFileToContainer(MountableFile.forHostPath("certs/redis.pem", 0644), "/tls/redis.pem")
            .withCopyFileToContainer(MountableFile.forHostPath("certs/redis-key.pem", 0644), "/tls/redis-key.pem")
            .withCopyFileToContainer(MountableFile.forHostPath("certs/ca.pem", 0644), "/tls/ca.pem")
            .withCopyFileToContainer(MountableFile.forHostPath("docker/redis/users.acl", 0644), "/etc/redis/users.acl")
            .withCommand("redis-server", "--port", "0", "--tls-port", "6379",
                    "--tls-cert-file", "/tls/redis.pem", "--tls-key-file", "/tls/redis-key.pem",
                    "--tls-ca-cert-file", "/tls/ca.pem", "--tls-auth-clients", "no",
                    "--aclfile", "/etc/redis/users.acl")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    private final List<LettuceConnectionFactory> factories = new ArrayList<>();

    @AfterEach
    void closeConnections() {
        factories.forEach(LettuceConnectionFactory::destroy);
    }

    @Test
    void theBackendRunsItsScriptsOnItsOwnKeys() {
        StringRedisTemplate backend = connect(USER, PASSWORD, true);

        // the token bucket Lua script (group 4): HMGET, HSET, PEXPIRE and TIME inside the script
        Decision decision = new RedisRateLimiter(backend, TestBreakers.redis()).tryAcquire("pgw-colombo-01",
                new RateLimitProperties.Limit(10, 5));
        assertThat(decision.allowed()).isTrue();

        // the login code check (group 6)
        String result = backend.execute(RedisScript.of(new ClassPathResource("redis/otp-verify.lua"), String.class),
                List.of("otp:94771234567"), "hash", "5");
        assertThat(result).isEqualTo("MISSING");

        backend.opsForValue().set("idem:pgw-colombo-01:k1", "{}", Duration.ofSeconds(30));
        assertThat(backend.opsForValue().get("idem:pgw-colombo-01:k1")).isEqualTo("{}");
    }

    @Test
    void theHealthCheckMayReadInfo() {
        Properties info = connect(USER, PASSWORD, true)
                .execute((RedisCallback<Properties>) connection -> connection.serverCommands().info("server"));

        assertThat(info).containsKey("redis_version");
    }

    @Test
    void theBackendCannotFlushOrReadOtherKeys() {
        StringRedisTemplate backend = connect(USER, PASSWORD, true);

        assertThatThrownBy(() -> backend.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        })).isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("NOPERM");
        assertThatThrownBy(() -> backend.opsForValue().get("someone-else:secret"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("NOPERM");
        assertThatThrownBy(() -> backend.keys("*"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("NOPERM");
    }

    @Test
    void theCacheUserReachesOnlyCacheKeys() {   // RD-10: it holds profiles, not login codes
        StringRedisTemplate cache = connect("telecom-cache", "dev-only-cache-password", true);

        cache.opsForValue().set("cache:v1:plans", "[]", Duration.ofSeconds(30));
        assertThat(cache.opsForValue().get("cache:v1:plans")).isEqualTo("[]");

        assertThatThrownBy(() -> cache.opsForValue().get("otp:94771234567"))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("NOPERM");
    }

    @Test
    void withoutAUserNobodyGetsIn() {   // the default user is off
        StringRedisTemplate anonymous = connect(null, null, true);

        assertThatThrownBy(() -> anonymous.opsForValue().get("otp:94771234567"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void aWrongPasswordIsRefused() {
        StringRedisTemplate guesser = connect(USER, "not-the-password", true);

        assertThatThrownBy(() -> guesser.opsForValue().get("otp:94771234567"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void plainTextIsRefused() {   // there is no plain port, only TLS
        StringRedisTemplate plain = connect(USER, PASSWORD, false);

        assertThatThrownBy(() -> plain.opsForValue().get("otp:94771234567"))
                .isInstanceOf(DataAccessException.class);
    }

    private StringRedisTemplate connect(String user, String password, boolean tls) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        server.setUsername(user);
        if (password != null) {
            server.setPassword(password);
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3));
        if (tls) {
            // trust only the dev CA, and check the certificate is for this host (localhost)
            client.useSsl().and().clientOptions(ClientOptions.builder()
                    .sslOptions(SslOptions.builder().jdkSslProvider()
                            .truststore(new File("certs/gateway-ca-truststore.p12"), "changeit").build())
                    .build());
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(server, client.build());
        factory.afterPropertiesSet();
        factory.start();
        factories.add(factory);
        return new StringRedisTemplate(factory);
    }
}
