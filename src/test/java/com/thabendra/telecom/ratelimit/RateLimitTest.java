package com.thabendra.telecom.ratelimit;

import com.github.dockerjava.api.DockerClient;
import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.idempotency.IdempotencyFilter;
import com.thabendra.telecom.soap.WebServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;

import java.util.Map;
import java.util.UUID;

import static com.thabendra.telecom.TestCerts.COLOMBO;
import static com.thabendra.telecom.TestCerts.KANDY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Group 4 against real Redis + Oracle: the Lua token bucket (RD-3), 429 + Retry-After per gateway
 * (RL-1, RL-2) over REST and SOAP, retries counting too, and the local fallback when Redis can't be
 * reached (RD-6). Skipped when Docker isn't running.
 */
@IntegrationTest
class RateLimitTest {

    private static final String MSISDN = "94771234567";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RedisRateLimiter shared;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("redis")
    private GenericContainer<?> redisContainer;

    @LocalServerPort
    private int gatewayPort;

    @BeforeEach
    void setUp() throws Exception {
        TestData.clean(jdbc, redis);
        mvc.perform(post("/api/subscribers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                         "planCode":"BASIC","initialBalance":100.00}""".formatted(MSISDN)))
                .andExpect(status().isCreated());
    }

    // the Lua script directly, with a small bucket: 3 burst, 2 per second

    @Test
    void luaBucketAllowsTheBurstThenRefusesThenRefills() throws Exception {
        RateLimitProperties.Limit small = new RateLimitProperties.Limit(3, 2);

        for (int i = 0; i < 3; i++) {
            assertThat(shared.tryAcquire("gw-test", small).allowed()).isTrue();
        }
        Decision refused = shared.tryAcquire("gw-test", small);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.waitMs()).isBetween(1L, 500L);   // one token every 500 ms

        Thread.sleep(600);
        assertThat(shared.tryAcquire("gw-test", small).allowed()).isTrue();
    }

    @Test
    void bucketKeyExpiresWhenIdle() {   // RD-8
        shared.tryAcquire("gw-test", new RateLimitProperties.Limit(3, 2));

        Long ttlMs = redis.execute((RedisCallback<Long>) connection ->
                connection.keyCommands().pTtl(RedisRateLimiter.redisKey("gw-test").getBytes()));
        assertThat(ttlMs).isBetween(1L, 1500L + 1000L);   // refilled after 3 / 2 per s, plus a second
    }

    // through the filter, with the configured limit (2000 burst, 1000/s) and an emptied bucket

    @Test
    void emptyBucketIs429WithRetryAfterAndNothingRuns() throws Exception {
        empty(COLOMBO);

        mvc.perform(start(COLOMBO))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.detail").value(containsString("pgw-colombo-01")));

        assertThat(sessions()).isZero();
    }

    @Test
    void anotherGatewaysBucketIsNotAffected() throws Exception {
        empty(COLOMBO);

        mvc.perform(start(KANDY)).andExpect(status().isCreated());
    }

    @Test
    void aRetryThatWouldBeReplayedIsRateLimitedToo() throws Exception {   // limiter runs before idempotency
        String key = UUID.randomUUID().toString();
        mvc.perform(start(COLOMBO, key)).andExpect(status().isCreated());

        empty(COLOMBO);

        mvc.perform(start(COLOMBO, key))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED_HEADER));
    }

    @Test
    void soapCallerGetsA429Fault() {
        empty(COLOMBO);

        ResponseEntity<String> answer = TestCerts.exchange(TestCerts.https(gatewayPort, COLOMBO).post()
                .uri("/ws")
                .contentType(MediaType.TEXT_XML)
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .body("""
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:s="%s">
                          <soapenv:Body><s:StartSessionRequest>
                            <s:msisdn>%s</s:msisdn><s:app>GENERAL</s:app><s:requestedMb>100</s:requestedMb>
                          </s:StartSessionRequest></soapenv:Body>
                        </soapenv:Envelope>""".formatted(WebServiceConfig.NAMESPACE, MSISDN)));

        assertThat(answer.getStatusCode().value()).isEqualTo(429);
        assertThat(answer.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(answer.getBody()).contains("SOAP-ENV:Fault", "Rate limit");
        assertThat(sessions()).isZero();
    }

    @Test
    void whenRedisIsDownTheLocalBucketKeepsChargingWorking() throws Exception {   // RD-6
        DockerClient docker = redisContainer.getDockerClient();
        docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            // the shared bucket can't be read; this copy's own bucket is full, so the call goes on
            mvc.perform(start(COLOMBO)).andExpect(status().isCreated());
        } finally {
            docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
    }

    private MockHttpServletRequestBuilder start(String gateway) {
        return start(gateway, UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder start(String gateway, String key) {
        return post("/charging/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .with(TestCerts.gateway(gateway))
                .header(IdempotencyFilter.KEY_HEADER, key)
                .content("{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":100}".formatted(MSISDN));
    }

    // 0 tokens, updatedAt a little in the future: at 1000 tokens/s the bucket would otherwise refill
    // in a millisecond, before the request arrives
    private void empty(String gateway) {
        Long redisNow = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
        redis.opsForHash().putAll(RedisRateLimiter.redisKey(gateway),
                Map.of("tokens", "0", "updatedAt", String.valueOf(redisNow + 10_000)));
    }

    private long sessions() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM charging_session", Long.class);
    }
}
