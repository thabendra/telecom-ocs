package com.thabendra.telecom;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "telecom.public-port=0")
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, TestTokens.Decoder.class, TestSms.Config.class})
@Testcontainers(disabledWithoutDocker = true)
public @interface IntegrationTest {
}
