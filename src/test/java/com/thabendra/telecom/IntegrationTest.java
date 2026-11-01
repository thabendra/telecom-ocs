package com.thabendra.telecom;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One setup for every integration test class, so they all share one Spring context and one pair of
 * Oracle + Redis containers. Both doors start on free ports: the mTLS gateway port (server.port, read
 * with @LocalServerPort) and the plain public port (ServerPorts.publicPort()). Gateway tokens are
 * checked with the test key from TestTokens; login SMS go to TestSms; TestUsers logs in to the public API.
 * Needs Docker, and the test certificates from scripts/generate-dev-certs.sh.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
// the shared test Redis is a plain container (RedisSecurityTest checks TLS + ACL on its own one)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"telecom.public-port=0", "management.server.port=0", "spring.data.redis.ssl.enabled=false",
                "telecom.charging.expire-sessions=false",   // tests run the expiry themselves
                // Docker on a laptop can be slow for a moment: only failures may open the Redis breaker here
                "telecom.circuit.redis.slow-call=30s",
                "telecom.circuit.signing-keys.refresh=false",
                // the cache shares the test Redis (RedisEvictionTest and RedisSecurityTest check its own setup)
                "telecom.cache.redis.host=", "telecom.cache.monitor=false"})
@AutoConfigureMockMvc
// metrics export (Prometheus) is off in tests unless asked for; tracing stays off
@AutoConfigureObservability(tracing = false)
@Import({TestcontainersConfig.class, TestTokens.Decoder.class, TestSms.Config.class})
@Testcontainers(disabledWithoutDocker = true)
public @interface IntegrationTest {
}
