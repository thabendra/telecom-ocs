package com.thabendra.telecom.charging;

import com.jayway.jsonpath.JsonPath;
import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestUsers;
import com.thabendra.telecom.idempotency.IdempotencyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static com.thabendra.telecom.TestCerts.COLOMBO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CH-8 against Oracle: a session whose gateway stopped reporting is closed, its held quota goes back
 * to the bucket, nothing is charged, and the audit says EXPIRED. The tests call the expiry directly
 * (the schedule is off in tests). Skipped when Docker isn't running.
 */
@IntegrationTest
class SessionExpiryTest {

    private static final String MSISDN = "94771234567";

    @Autowired
    private SessionExpiry expiry;

    @Autowired
    private MockMvc mvc;

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

    @Test
    void aForgottenSessionGivesItsQuotaBackAndChargesNothing() throws Exception {
        String session = start(500);
        assertThat(subscriber().get("PLAN_DATA_RESERVED_MB")).hasToString("500");
        lastUpdate(session, "INTERVAL '2' HOUR");   // the gateway went quiet two hours ago

        expiry.expireStale();

        assertThat(subscriber().get("PLAN_DATA_RESERVED_MB")).hasToString("0");
        assertThat(subscriber().get("PLAN_DATA_USED_MB")).hasToString("0");
        Map<String, Object> closed = jdbc.queryForMap(
                "SELECT status, end_reason, released_at FROM charging_session WHERE id = ?", session);
        assertThat(closed.get("STATUS")).isEqualTo("RELEASED");
        assertThat(closed.get("END_REASON")).isEqualTo("EXPIRED");
        assertThat(closed.get("RELEASED_AT")).isNotNull();
        assertThat(jdbc.queryForMap("SELECT event, gateway, actor FROM audit_event WHERE session_id = ? AND event = 'EXPIRED'",
                session)).containsEntry("GATEWAY", COLOMBO).containsEntry("ACTOR", "system");
    }

    @Test
    void anActiveSessionIsKept() throws Exception {
        String session = start(100);

        expiry.expireStale();

        assertThat(sessionStatus(session)).isEqualTo("OPEN");
    }

    @Test
    void halfAnHourQuietIsNotStale() throws Exception {
        // guards the time zone lesson: a plain TIMESTAMP compared with SYSTIMESTAMP would be read in the
        // session's zone (+05:30) and this session would look five and a half hours old
        String session = start(100);
        lastUpdate(session, "INTERVAL '30' MINUTE");

        expiry.expireStale();

        assertThat(sessionStatus(session)).isEqualTo("OPEN");
    }

    @Test
    void anExpiredSessionCannotBeUsedAnyMore() throws Exception {
        String session = start(100);
        lastUpdate(session, "INTERVAL '2' HOUR");
        expiry.expireStale();

        mvc.perform(post("/charging/v1/sessions/{id}/update", session).with(TestCerts.gateway(COLOMBO))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestNumber\":1,\"usedMb\":50,\"requestedMb\":100}"))
                .andExpect(status().isConflict());   // ORA-20008 already released
    }

    @Test
    void runningItAgainFindsNothingNew() throws Exception {
        String session = start(100);
        lastUpdate(session, "INTERVAL '2' HOUR");

        int first = expiry.expireStale();
        int second = expiry.expireStale();

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    private String start(long requestedMb) throws Exception {
        String body = mvc.perform(post("/charging/v1/sessions").with(TestCerts.gateway(COLOMBO))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":%d}".formatted(MSISDN, requestedMb)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.sessionId");
    }

    // updated_at holds the database's wall clock, so move it with the same clock
    private void lastUpdate(String session, String ago) {
        jdbc.update("UPDATE charging_session SET updated_at = CAST(SYSTIMESTAMP AS TIMESTAMP) - " + ago
                + " WHERE id = ?", session);
    }

    private Map<String, Object> subscriber() {
        return jdbc.queryForMap("SELECT plan_data_reserved_mb, plan_data_used_mb FROM subscriber WHERE msisdn = ?", MSISDN);
    }

    private String sessionStatus(String session) {
        return jdbc.queryForObject("SELECT status FROM charging_session WHERE id = ?", String.class, session);
    }
}
