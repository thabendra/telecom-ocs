package com.thabendra.telecom.charging;

import com.jayway.jsonpath.JsonPath;
import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.idempotency.IdempotencyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Online charging (CH-1..CH-4) end to end over REST against Oracle: grants from the right bucket,
 * reserve -> commit, reserved quota hidden from other sessions, DENY when nothing is left.
 * Skipped when Docker isn't running.
 */
@IntegrationTest
class ChargingControllerTest {

    private static final String MSISDN = "94771234567";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    private final Map<String, Long> requestNumbers = new HashMap<>();

    @BeforeEach
    void cleanDatabase() {
        TestData.clean(jdbc, redis);
    }

    @Test
    void startGrantsFromPlanAndHoldsIt() throws Exception {
        createSubscriber("100.00");

        start("GENERAL", 100)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("ALLOW"))
                .andExpect(jsonPath("$.grantedMb").value(100))
                .andExpect(jsonPath("$.source").value("PLAN"))
                .andExpect(jsonPath("$.bandwidthKbps").value(10240))
                .andExpect(jsonPath("$.validitySeconds").value(3600));

        assertThat(subscriberColumn("plan_data_reserved_mb")).isEqualTo(100);
        assertThat(subscriberColumn("plan_data_used_mb")).isZero();
    }

    @Test
    void updateChargesWhatWasUsedAndGrantsNextChunk() throws Exception {
        createSubscriber("100.00");
        String session = sessionId(start("GENERAL", 100));

        update(session, 60, 100)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ALLOW"))
                .andExpect(jsonPath("$.grantedMb").value(100));

        assertThat(subscriberColumn("plan_data_used_mb")).isEqualTo(60);       // only the used part
        assertThat(subscriberColumn("plan_data_reserved_mb")).isEqualTo(100);  // the new grant
        assertThat(jdbc.queryForObject("SELECT data_mb FROM usage_record WHERE usage_ref = ?", Long.class, session))
                .isEqualTo(60);
    }

    @Test
    void releaseChargesUsedAndFreesTheRest() throws Exception {
        createSubscriber("100.00");
        String session = sessionId(start("GENERAL", 100));

        release(session, 40)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsedMb").value(40));

        assertThat(subscriberColumn("plan_data_used_mb")).isEqualTo(40);
        assertThat(subscriberColumn("plan_data_reserved_mb")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM charging_session WHERE id = ?", String.class, session))
                .isEqualTo("RELEASED");
    }

    @Test
    void youtubeTrafficIsGrantedFromYoutubePack() throws Exception {
        createSubscriber("1000.00");
        activate("YT_UNLIMITED");

        start("YOUTUBE", 500)
                .andExpect(jsonPath("$.source").value("PACKAGE"))
                .andExpect(jsonPath("$.packageCode").value("YT_UNLIMITED"))
                .andExpect(jsonPath("$.bandwidthKbps").value(2048));

        assertThat(subscriberColumn("plan_data_reserved_mb")).isZero();
    }

    @Test
    void grantIsLimitedToWhatTheBucketHasThenMovesOn() throws Exception {
        createSubscriber("500.00");
        activate("WA_2GB");

        String session = sessionId(start("WHATSAPP", 3000)
                .andExpect(jsonPath("$.grantedMb").value(2048))
                .andExpect(jsonPath("$.packageCode").value("WA_2GB")));

        update(session, 2048, 100)
                .andExpect(jsonPath("$.source").value("PLAN"))
                .andExpect(jsonPath("$.grantedMb").value(100));
    }

    @Test
    void reservedQuotaIsNotGivenToAnotherSession() throws Exception {
        createSubscriber("100.00");
        start("GENERAL", 10240).andExpect(jsonPath("$.source").value("PLAN"));   // holds all BASIC data

        // second app at the same time: plan data is held, so it falls to pay as you go
        start("YOUTUBE", 100)
                .andExpect(jsonPath("$.result").value("ALLOW"))
                .andExpect(jsonPath("$.source").value("BALANCE"))
                .andExpect(jsonPath("$.bandwidthKbps").value(1024));
    }

    @Test
    void payAsYouGoHoldsMoneyAndChargesOnlyWhatWasUsed() throws Exception {
        createSubscriber("10.00");
        start("GENERAL", 10240);

        // 10 LKR at 0.50 per MB = 20 MB
        String session = sessionId(start("YOUTUBE", 100)
                .andExpect(jsonPath("$.grantedMb").value(20)));
        assertThat(subscriberMoney("balance_reserved")).isEqualByComparingTo("10.00");

        release(session, 8);

        assertThat(subscriberMoney("balance")).isEqualByComparingTo("6.00");
        assertThat(subscriberMoney("balance_reserved")).isEqualByComparingTo("0");
    }

    @Test
    void nothingLeftAnywhereIsDenyWithBlock() throws Exception {
        createSubscriber("0.00");
        start("GENERAL", 10240);

        start("GENERAL", 100)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("DENY"))
                .andExpect(jsonPath("$.grantedMb").value(0))
                .andExpect(jsonPath("$.action").value("BLOCK"));
    }

    @Test
    void reportAboveGrantIsCappedAtGrant() throws Exception {
        createSubscriber("100.00");
        String session = sessionId(start("GENERAL", 100));

        release(session, 500).andExpect(jsonPath("$.totalUsedMb").value(100));
        assertThat(subscriberColumn("plan_data_used_mb")).isEqualTo(100);
    }

    @Test
    void releasingTwiceIsConflict() throws Exception {
        createSubscriber("100.00");
        String session = sessionId(start("GENERAL", 100));
        release(session, 10).andExpect(status().isOk());

        release(session, 10).andExpect(status().isConflict());
        assertThat(subscriberColumn("plan_data_used_mb")).isEqualTo(10);
    }

    @Test
    void unknownSessionIsNotFound() throws Exception {
        update("00000000-0000-0000-0000-000000000000", 0, 100).andExpect(status().isNotFound());
    }

    @Test
    void unknownSubscriberIsNotFound() throws Exception {
        start("GENERAL", 100).andExpect(status().isNotFound());
    }

    private void createSubscriber(String balance) throws Exception {
        mvc.perform(post("/api/subscribers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                         "planCode":"BASIC","initialBalance":%s}""".formatted(MSISDN, balance)))
                .andExpect(status().isCreated());
    }

    private void activate(String packageCode) throws Exception {
        mvc.perform(post("/api/subscribers/{msisdn}/packages", MSISDN).contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .content("{\"packageCode\":\"%s\"}".formatted(packageCode)))
                .andExpect(status().isCreated());
    }

    // every call gets a new Idempotency-Key, like a gateway sending a new request, and comes from
    // pgw-colombo-01's certificate (the charging API is gateway only)
    private ResultActions start(String app, long requestedMb) throws Exception {
        return mvc.perform(post("/charging/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .with(TestCerts.gateway(TestCerts.COLOMBO))
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .content("{\"msisdn\":\"%s\",\"app\":\"%s\",\"requestedMb\":%d}".formatted(MSISDN, app, requestedMb)));
    }

    private ResultActions update(String sessionId, long usedMb, long requestedMb) throws Exception {
        return mvc.perform(post("/charging/v1/sessions/{id}/update", sessionId).contentType(MediaType.APPLICATION_JSON)
                .with(TestCerts.gateway(TestCerts.COLOMBO))
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .content("{\"requestNumber\":%d,\"usedMb\":%d,\"requestedMb\":%d}"
                        .formatted(nextRequestNumber(sessionId), usedMb, requestedMb)));
    }

    private ResultActions release(String sessionId, long usedMb) throws Exception {
        return mvc.perform(post("/charging/v1/sessions/{id}/release", sessionId).contentType(MediaType.APPLICATION_JSON)
                .with(TestCerts.gateway(TestCerts.COLOMBO))
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .content("{\"requestNumber\":%d,\"usedMb\":%d}".formatted(nextRequestNumber(sessionId), usedMb)));
    }

    // the gateway's counter per session: start is 0, then 1, 2, ...
    private long nextRequestNumber(String sessionId) {
        return requestNumbers.merge(sessionId, 1L, Long::sum);
    }

    private static String sessionId(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.sessionId");
    }

    private long subscriberColumn(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM subscriber WHERE msisdn = ?", Long.class, MSISDN);
    }

    private BigDecimal subscriberMoney(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM subscriber WHERE msisdn = ?", BigDecimal.class, MSISDN);
    }
}
