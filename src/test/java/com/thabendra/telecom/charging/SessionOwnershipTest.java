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
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static com.thabendra.telecom.TestCerts.COLOMBO;
import static com.thabendra.telecom.TestCerts.KANDY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Group 8 against Oracle + Redis: a session belongs to the gateway that started it (CH-7), checked
 * first in the session cache (RD-4) and always in Oracle; how a session ended (end_reason); and an
 * update asking for 0 MB only reports usage (REPORTED, no BLOCK). Skipped when Docker isn't running.
 */
@IntegrationTest
class SessionOwnershipTest {

    private static final String MSISDN = "94771234567";

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

    // CH-7

    @Test
    void anotherGatewayCannotUpdateOrReleaseTheSession() throws Exception {
        String session = start(COLOMBO);

        update(KANDY, session, 1, 50, 100).andExpect(status().isNotFound());
        release(KANDY, session, 1, 50).andExpect(status().isNotFound());

        // the owner goes on as normal: the refusals used up no request number and charged nothing
        update(COLOMBO, session, 1, 50, 100).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ALLOW"));
        assertThat(column(session, "total_used_mb")).isEqualTo("50");
    }

    @Test
    void theAnswerIsTheSameAsForASessionThatDoesNotExist() throws Exception {   // no hint that it exists
        String session = start(COLOMBO);

        String foreign = update(KANDY, session, 1, 0, 100).andReturn().getResponse().getContentAsString();
        String unknown = update(KANDY, "00000000-0000-0000-0000-000000000000", 1, 0, 100)
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(foreign, "$.detail"))
                .isEqualTo(JsonPath.<String>read(unknown, "$.detail").replace("00000000-0000-0000-0000-000000000000", session));
    }

    @Test
    void oracleRefusesTooWhenTheCacheHasNoEntry() throws Exception {   // Redis lost the key: still safe
        String session = start(COLOMBO);
        redis.delete(SessionCache.key(session));

        update(KANDY, session, 1, 50, 100).andExpect(status().isNotFound());   // ORA-20012 from the PL/SQL

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM charging_request WHERE session_id = ? AND request_number = 1",
                Integer.class, session)).isZero();
    }

    @Test
    void theCacheAnswersBeforeOracleIsAsked() throws Exception {   // RD-4
        String session = start(COLOMBO);
        // pretend the cache says Kandy owns it: Colombo is then refused without Oracle being asked
        redis.opsForValue().set(SessionCache.key(session), KANDY + "|" + MSISDN);

        update(COLOMBO, session, 1, 50, 100).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM charging_request WHERE session_id = ?",
                Integer.class, session)).isEqualTo(1);   // only the start: the update never reached Oracle
    }

    @Test
    void theCacheKnowsTheOwnerWhileTheSessionIsOpen() throws Exception {
        String session = start(COLOMBO);

        assertThat(redis.opsForValue().get(SessionCache.key(session))).isEqualTo(COLOMBO + "|" + MSISDN);
        assertThat(redis.getExpire(SessionCache.key(session))).isBetween(60L * 60, 65L * 60);   // the expiry (CH-8)

        release(COLOMBO, session, 1, 10).andExpect(status().isOk());
        assertThat(redis.hasKey(SessionCache.key(session))).isFalse();
    }

    @Test
    void theSessionKeepsItsGatewayAndHowItEnded() throws Exception {
        String session = start(COLOMBO);
        assertThat(column(session, "gateway")).isEqualTo(COLOMBO);

        release(COLOMBO, session, 1, 10).andExpect(status().isOk());

        assertThat(column(session, "end_reason")).isEqualTo("RELEASE");
    }

    @Test
    void aDenyAtStartEndsTheSessionAsDeny() throws Exception {
        start(COLOMBO, 10240);   // all plan data, no money

        String denied = JsonPath.read(startRequest(COLOMBO, 100).andReturn().getResponse().getContentAsString(),
                "$.sessionId");

        assertThat(column(denied, "end_reason")).isEqualTo("DENY");
        assertThat(redis.hasKey(SessionCache.key(denied))).isFalse();   // a closed session isn't cached
    }

    // the 0 MB quirk: only a report, never a block

    @Test
    void anUpdateAskingForNothingChargesTheUsageAndBlocksNobody() throws Exception {
        String session = start(COLOMBO);

        update(COLOMBO, session, 1, 60, 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("REPORTED"))
                .andExpect(jsonPath("$.grantedMb").value(0))
                .andExpect(jsonPath("$.action").doesNotExist());

        assertThat(column(session, "total_used_mb")).isEqualTo("60");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_command WHERE msisdn = ?", Integer.class, MSISDN))
                .isZero();
        release(COLOMBO, session, 2, 0).andExpect(status().isOk());   // the session is still open
    }

    private String start(String gateway) throws Exception {
        return start(gateway, 100);
    }

    private String start(String gateway, long requestedMb) throws Exception {
        String body = startRequest(gateway, requestedMb).andExpect(jsonPath("$.result").value("ALLOW"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.sessionId");
    }

    private ResultActions startRequest(String gateway, long requestedMb) throws Exception {
        return mvc.perform(post("/charging/v1/sessions").with(TestCerts.gateway(gateway))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":%d}".formatted(MSISDN, requestedMb)))
                .andExpect(status().isCreated());
    }

    private ResultActions update(String gateway, String session, long number, long usedMb, long requestedMb)
            throws Exception {
        return mvc.perform(post("/charging/v1/sessions/{id}/update", session).with(TestCerts.gateway(gateway))
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestNumber\":%d,\"usedMb\":%d,\"requestedMb\":%d}".formatted(number, usedMb, requestedMb)));
    }

    private ResultActions release(String gateway, String session, long number, long usedMb) throws Exception {
        return mvc.perform(post("/charging/v1/sessions/{id}/release", session).with(TestCerts.gateway(gateway))
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestNumber\":%d,\"usedMb\":%d}".formatted(number, usedMb)));
    }

    private String column(String session, String name) {
        return jdbc.queryForObject("SELECT " + name + " FROM charging_session WHERE id = ?", String.class, session);
    }
}
