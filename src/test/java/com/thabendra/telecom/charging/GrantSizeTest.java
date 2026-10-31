package com.thabendra.telecom.charging;

import com.jayway.jsonpath.JsonPath;
import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestUsers;
import com.thabendra.telecom.idempotency.IdempotencyFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static com.thabendra.telecom.TestCerts.COLOMBO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CH-5 and RL-3 against Oracle + Redis: the grant size when the gateway leaves it to us, heavy users in
 * the sliding window getting bigger grants (and an alert, never a block), an asked amount honoured,
 * and a smaller final grant when little is left. Skipped when Docker isn't running.
 */
@IntegrationTest
class GrantSizeTest {

    private static final String MSISDN = "94771234567";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UsageWindow usage;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() throws Exception {
        TestData.clean(jdbc, redis);
        mvc.perform(post("/api/subscribers").with(TestUsers.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                                 "planCode":"BASIC","initialBalance":0}""".formatted(MSISDN)))
                .andExpect(status().isCreated());
    }

    // CH-5

    @Test
    void leftToUsAGrantIs100Mb() throws Exception {
        start(null).andExpect(jsonPath("$.result").value("ALLOW")).andExpect(jsonPath("$.grantedMb").value(100));
    }

    @Test
    void anAskedAmountIsHonoured() throws Exception {
        usage.add(MSISDN, 5000);   // heavy, but the gateway said what it wants

        start(500L).andExpect(jsonPath("$.grantedMb").value(500));
    }

    @Test
    void whenLittleIsLeftTheFinalGrantIsSmaller() throws Exception {
        start(10_200L).andExpect(jsonPath("$.grantedMb").value(10_200));   // BASIC has 10 240 MB

        start(null)
                .andExpect(jsonPath("$.result").value("ALLOW"))
                .andExpect(jsonPath("$.grantedMb").value(40))
                .andExpect(jsonPath("$.source").value("PLAN"));
    }

    @Test
    void anUpdateLeftToUsGetsTheDefaultToo() throws Exception {
        String session = sessionId(start(100L));

        update(session, 1, 100, null).andExpect(jsonPath("$.grantedMb").value(100));
    }

    // RL-3

    @Test
    void aHeavyUserGetsBiggerGrants() throws Exception {
        usage.add(MSISDN, 2048);   // 2 GB in the last 15 minutes

        start(null).andExpect(jsonPath("$.result").value("ALLOW")).andExpect(jsonPath("$.grantedMb").value(250));
    }

    @Test
    void theReportThatCrossesTheLineAlreadyGetsTheBigGrant() throws Exception {
        usage.add(MSISDN, 1950);
        String session = sessionId(start(100L));

        update(session, 1, 100, null).andExpect(jsonPath("$.grantedMb").value(250));   // 1950 + 100 >= 2048
        assertThat(usage.total(MSISDN)).isEqualTo(2050);
    }

    @Test
    void beingHeavyNeverBlocks() throws Exception {
        usage.add(MSISDN, 50_000);

        start(null).andExpect(jsonPath("$.result").value("ALLOW"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_command WHERE msisdn = ?", Integer.class, MSISDN))
                .isZero();
    }

    @Test
    void crossingTheLineIsCountedOnceForTheAlert() {
        double before = flagged();

        usage.add(MSISDN, 2000);   // not yet
        usage.add(MSISDN, 100);    // crosses 2048
        usage.add(MSISDN, 100);    // still heavy: not a new alert

        assertThat(flagged() - before).isEqualTo(1);
    }

    @Test
    void oldUsageSlidesOutOfTheWindow() {
        Long redisNow = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
        redis.opsForZSet().add(UsageWindow.key(MSISDN), "old:3000", redisNow - 16 * 60_000);   // 16 min ago

        assertThat(usage.total(MSISDN)).isZero();
        assertThat(usage.add(MSISDN, 10)).isEqualTo(10);
        assertThat(redis.getExpire(UsageWindow.key(MSISDN))).isBetween(1L, 15L * 60);   // goes away when quiet
    }

    @Test
    void releaseUsageCountsAsUsageToo() throws Exception {
        String session = sessionId(start(100L));

        release(session, 1, 80).andExpect(status().isOk());

        assertThat(usage.total(MSISDN)).isEqualTo(80);
    }

    private double flagged() {
        Counter counter = meters.find("ocs.heavy.users.flagged").counter();
        return counter == null ? 0 : counter.count();
    }

    private ResultActions start(Long requestedMb) throws Exception {
        String size = requestedMb == null ? "" : ",\"requestedMb\":" + requestedMb;
        return mvc.perform(post("/charging/v1/sessions").with(TestCerts.gateway(COLOMBO))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"msisdn\":\"%s\",\"app\":\"GENERAL\"%s}".formatted(MSISDN, size)))
                .andExpect(status().isCreated());
    }

    private ResultActions update(String session, long number, long usedMb, Long requestedMb) throws Exception {
        String size = requestedMb == null ? "" : ",\"requestedMb\":" + requestedMb;
        return mvc.perform(post("/charging/v1/sessions/{id}/update", session).with(TestCerts.gateway(COLOMBO))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestNumber\":%d,\"usedMb\":%d%s}".formatted(number, usedMb, size)))
                .andExpect(status().isOk());
    }

    private ResultActions release(String session, long number, long usedMb) throws Exception {
        return mvc.perform(post("/charging/v1/sessions/{id}/release", session).with(TestCerts.gateway(COLOMBO))
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestNumber\":%d,\"usedMb\":%d}".formatted(number, usedMb)));
    }

    private static String sessionId(ResultActions started) throws Exception {
        return JsonPath.read(started.andReturn().getResponse().getContentAsString(), "$.sessionId");
    }
}
