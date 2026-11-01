package com.thabendra.telecom.resilience;

import com.github.dockerjava.api.DockerClient;
import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestUsers;
import com.thabendra.telecom.idempotency.IdempotencyFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;

import java.util.UUID;

import static com.thabendra.telecom.TestCerts.COLOMBO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CB-4 against real Oracle + Redis: Redis hangs (the container is paused) -> the breaker opens -> charging
 * goes on, without the Redis timeouts -> Redis comes back -> test calls -> closed. Skipped when Docker
 * isn't running.
 */
@IntegrationTest
class RedisCircuitTest {

    private static final String MSISDN = "94771234567";

    @Autowired
    private RedisBreaker breaker;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    @Qualifier("redis")
    private GenericContainer<?> redisContainer;

    @LocalManagementPort
    private int managementPort;

    private boolean paused;

    @BeforeEach
    void setUp() throws Exception {
        breaker.reset();
        TestData.clean(jdbc, redis);
        mvc.perform(post("/api/subscribers").with(TestUsers.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                                 "planCode":"BASIC","initialBalance":0}""".formatted(MSISDN)))
                .andExpect(status().isCreated());
    }

    // whatever a test does, the next test class gets a running Redis and a closed breaker
    @AfterEach
    void restore() {
        if (paused) {
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
        breaker.reset();
    }

    @Test
    void aHangingRedisOpensTheBreakerAndChargingGoesOnFast() throws Exception {
        pauseRedis();

        startSessionsUntilTheBreakerOpens();

        long began = System.nanoTime();
        startSession();
        long tookMs = (System.nanoTime() - began) / 1_000_000;
        // with the breaker closed one request waits 500 ms per Redis call, several times over
        assertThat(tookMs).as("no Redis timeouts any more").isLessThan(1000);
        assertThat(meters.get("ocs.circuit.state").tag("name", "redis").tag("state", "open").gauge().value())
                .isEqualTo(1);
        assertThat(meters.get("ocs.circuit.rejected").tag("name", "redis").counter().count()).isPositive();
    }

    @Test
    void loginHasNoFallbackSoItSaysTryAgainSoon() throws Exception {
        pauseRedis();
        startSessionsUntilTheBreakerOpens();

        mvc.perform(post("/api/auth/otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"msisdn\":\"%s\"}".formatted(MSISDN)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void theHealthPageShowsTheOpenBreaker() throws Exception {
        pauseRedis();
        startSessionsUntilTheBreakerOpens();

        ResponseEntity<String> health = TestCerts.exchange(TestCerts.http(managementPort).get().uri("/actuator/health"));

        assertThat(health.getBody()).contains("\"circuit\"", "\"redis\":\"OPEN\"");
    }

    @Test
    void whenRedisIsBackTestCallsCloseTheBreaker() throws Exception {
        pauseRedis();
        startSessionsUntilTheBreakerOpens();

        redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        paused = false;

        long deadline = System.nanoTime() + 30_000_000_000L;   // it waits 5 s open, then a few test calls
        while (breaker.state() != CircuitBreaker.State.CLOSED && System.nanoTime() < deadline) {
            startSession();
            Thread.sleep(200);
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    private void pauseRedis() {
        DockerClient docker = redisContainer.getDockerClient();
        docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
        paused = true;
    }

    private void startSessionsUntilTheBreakerOpens() throws Exception {
        for (int i = 0; i < 20 && breaker.state() != CircuitBreaker.State.OPEN; i++) {
            startSession();   // works all the time: every Redis caller has a fallback
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private void startSession() throws Exception {
        mvc.perform(post("/charging/v1/sessions").with(TestCerts.gateway(COLOMBO))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":10}".formatted(MSISDN)))
                .andExpect(status().isCreated());
    }
}
