package com.thabendra.telecom.ratelimit;

import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PR-3 against real Redis: the public API's buckets per IP (nobody logged in), per customer and per
 * staff login, each with 429 + Retry-After. Skipped when Docker isn't running.
 */
@IntegrationTest
class PublicRateLimitTest {

    private static final String MSISDN = "94771234567";
    private static final String MOCK_MVC_IP = "127.0.0.1";   // MockMvc's remote address

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        TestData.clean(jdbc, redis);
    }

    @Test
    void anIpWithAnEmptyBucketGets429OnTheLogin() throws Exception {
        empty("ip:" + MOCK_MVC_IP);

        mvc.perform(post("/api/auth/otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"msisdn\":\"%s\"}".formatted(MSISDN)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2"))   // 1 token at 0.5 per second
                .andExpect(jsonPath("$.status").value(429));
        mvc.perform(get("/api/plans")).andExpect(status().isTooManyRequests());
    }

    @Test
    void aLoggedInCustomerIsCountedByNumberNotByIp() throws Exception {   // many phones share one IP (CGNAT)
        empty("ip:" + MOCK_MVC_IP);

        mvc.perform(get("/api/plans").with(TestUsers.customer(MSISDN))).andExpect(status().isOk());
    }

    @Test
    void aCustomerWithAnEmptyBucketGets429() throws Exception {
        empty("user:" + MSISDN);

        mvc.perform(get("/api/plans").with(TestUsers.customer(MSISDN))).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/plans").with(TestUsers.customer("94777654321"))).andExpect(status().isOk());
    }

    @Test
    void staffHaveTheirOwnBucketPerLogin() throws Exception {
        empty("staff:" + TestUsers.CARE_AGENT);

        mvc.perform(get("/api/plans").with(TestUsers.careAgent())).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/plans").with(TestUsers.admin())).andExpect(status().isOk());
    }

    @Test
    void eachRequestTakesOneTokenFromTheBucket() throws Exception {
        mvc.perform(get("/api/plans")).andExpect(status().isOk());

        // anonymous: 30 burst, so 29 left (and a little refill)
        Object tokens = redis.opsForHash().get(RedisRateLimiter.redisKey("ip:" + MOCK_MVC_IP), "tokens");
        assertThat(Double.parseDouble((String) tokens)).isBetween(29.0, 29.9);
    }

    // 0 tokens, updatedAt in the future, so no refill before the request arrives (as in RateLimitTest)
    private void empty(String bucket) {
        Long redisNow = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
        redis.opsForHash().putAll(RedisRateLimiter.redisKey(bucket),
                Map.of("tokens", "0", "updatedAt", String.valueOf(redisNow + 10_000)));
    }
}
