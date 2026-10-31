package com.thabendra.telecom.audit;

import com.jayway.jsonpath.JsonPath;
import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestUsers;
import com.thabendra.telecom.idempotency.IdempotencyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.thabendra.telecom.TestCerts.COLOMBO;
import static com.thabendra.telecom.TestCerts.KANDY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OB-1 against Oracle: GRANT / DENY written by the CHARGING package with the calling gateway,
 * BLOCK / UNBLOCK / ACKED written by triggers on gateway_command, SUSPEND / UNSUSPEND with the staff
 * login, nothing for a rolled-back request, the table refusing changes, and the staff-only read API.
 * Skipped when Docker isn't running.
 */
@IntegrationTest
class AuditTest {

    private static final String MSISDN = "94771234567";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        TestData.clean(jdbc, redis);
    }

    @Test
    void aGrantIsRecordedWithTheGatewayThatAsked() throws Exception {
        createSubscriber("0.00");

        start(KANDY, 500);

        assertThat(events()).singleElement().satisfies(row -> {
            assertThat(row.get("EVENT")).isEqualTo("GRANT");
            assertThat(row.get("GATEWAY")).isEqualTo(KANDY);
            assertThat(row.get("ACTOR")).isEqualTo(KANDY);
            assertThat(row.get("SOURCE")).isEqualTo("PLAN");
            assertThat(((Number) row.get("REQUESTED_MB")).longValue()).isEqualTo(500);
            assertThat(((Number) row.get("GRANTED_MB")).longValue()).isEqualTo(500);
            assertThat(((Number) row.get("BANDWIDTH_KBPS")).intValue()).isEqualTo(10240);   // BASIC plan
        });
    }

    @Test
    void aDenyAndTheBlockToEveryGatewayAreRecorded() throws Exception {
        createSubscriber("0.00");
        start(COLOMBO, 10240);   // all plan data
        start(COLOMBO, 100);     // nothing left: DENY + BLOCK (group 5)

        assertThat(eventNames()).containsExactly("GRANT", "DENY", "BLOCK", "BLOCK");
        assertThat(events("DENY")).singleElement().satisfies(row -> {
            assertThat(row.get("GATEWAY")).isEqualTo(COLOMBO);
            assertThat(((Number) row.get("GRANTED_MB")).longValue()).isZero();
            assertThat(row.get("SOURCE")).isNull();
        });
        assertThat(events("BLOCK")).extracting(row -> row.get("GATEWAY")).containsExactlyInAnyOrder(COLOMBO, KANDY);
        assertThat(events("BLOCK")).allSatisfy(row -> {
            assertThat(row.get("REASON")).isEqualTo("OUT_OF_QUOTA");
            assertThat(row.get("COMMAND_ID")).isNotNull();
        });
    }

    @Test
    void anAckIsRecordedAsTheGatewaysOwn() throws Exception {
        createSubscriber("0.00");
        start(COLOMBO, 10240);
        start(COLOMBO, 100);
        String body = mvc.perform(get("/charging/v1/commands").with(TestCerts.gateway(COLOMBO)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$[0].id");

        mvc.perform(post("/charging/v1/commands/{id}/ack", id).with(TestCerts.gateway(COLOMBO)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/charging/v1/commands/{id}/ack", id).with(TestCerts.gateway(COLOMBO)))
                .andExpect(status().isNoContent());   // acked twice, recorded once

        assertThat(events("ACKED")).singleElement().satisfies(row -> {
            assertThat(row.get("COMMAND_ID")).isEqualTo(id);
            assertThat(row.get("ACTOR")).isEqualTo(COLOMBO);
        });
    }

    @Test
    void aSuspensionIsRecordedWithTheStaffMember() throws Exception {
        createSubscriber("100.00");

        mvc.perform(put("/api/subscribers/{msisdn}/suspension", MSISDN).with(TestUsers.careAgent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"FRAUD\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/subscribers/{msisdn}/suspension", MSISDN).with(TestUsers.admin()))
                .andExpect(status().isOk());

        assertThat(events("SUSPEND")).singleElement()
                .satisfies(row -> assertThat(row.get("ACTOR")).isEqualTo(TestUsers.CARE_AGENT));
        assertThat(events("UNSUSPEND")).singleElement()
                .satisfies(row -> assertThat(row.get("ACTOR")).isEqualTo(TestUsers.ADMIN));
        assertThat(events("BLOCK")).hasSize(2).allSatisfy(row -> assertThat(row.get("REASON")).isEqualTo("FRAUD"));
        assertThat(events("UNBLOCK")).hasSize(2)
                .allSatisfy(row -> assertThat(row.get("REASON")).isEqualTo("UNSUSPENDED"));
    }

    @Test
    void aRefusedRequestLeavesNoAuditRow() throws Exception {   // same transaction as the decision
        createSubscriber("0.00");
        String session = sessionId(start(COLOMBO, 100));
        update(session, 1, 50, 100).andExpect(status().isOk());

        update(session, 1, 50, 100).andExpect(status().isConflict());   // request 1 again: ORA-20009, rolled back

        assertThat(eventNames()).containsExactly("GRANT", "GRANT");
    }

    @Test
    void aReportAskingForNothingIsNoDecisionInTheAudit() throws Exception {
        createSubscriber("0.00");
        String session = sessionId(start(COLOMBO, 100));

        update(session, 1, 100, 0).andExpect(status().isOk());

        assertThat(events("GRANT")).hasSize(1);
        assertThat(events("DENY")).isEmpty();
    }

    @Test
    void theAuditTrailCannotBeChanged() throws Exception {
        createSubscriber("0.00");
        start(COLOMBO, 100);

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET actor = 'someone'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(events()).hasSize(1);
    }

    @Test
    void staffReadTheAuditNewestFirst() throws Exception {
        createSubscriber("0.00");
        start(COLOMBO, 10240);
        start(COLOMBO, 100);

        mvc.perform(get("/api/subscribers/{msisdn}/audit", MSISDN).with(TestUsers.careAgent()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[3].event").value("GRANT"))
                .andExpect(jsonPath("$[3].gateway").value(COLOMBO))
                .andExpect(jsonPath("$[3].time").isNotEmpty());
        mvc.perform(get("/api/subscribers/{msisdn}/audit?size=1", MSISDN).with(TestUsers.admin()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void customersCannotReadTheAudit() throws Exception {
        mvc.perform(get("/api/subscribers/{msisdn}/audit", MSISDN).with(TestUsers.customer(MSISDN)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/subscribers/{msisdn}/audit", MSISDN)).andExpect(status().isUnauthorized());
    }

    private void createSubscriber(String balance) throws Exception {
        mvc.perform(post("/api/subscribers").with(TestUsers.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                                 "planCode":"BASIC","initialBalance":%s}""".formatted(MSISDN, balance)))
                .andExpect(status().isCreated());
    }

    private ResultActions start(String gateway, long requestedMb) throws Exception {
        return mvc.perform(post("/charging/v1/sessions").with(TestCerts.gateway(gateway))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":%d}".formatted(MSISDN, requestedMb)))
                .andExpect(status().isCreated());
    }

    private ResultActions update(String session, long requestNumber, long usedMb, long requestedMb) throws Exception {
        return mvc.perform(post("/charging/v1/sessions/{id}/update", session).with(TestCerts.gateway(COLOMBO))
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestNumber\":%d,\"usedMb\":%d,\"requestedMb\":%d}"
                        .formatted(requestNumber, usedMb, requestedMb)));
    }

    private static String sessionId(ResultActions started) throws Exception {
        return JsonPath.read(started.andReturn().getResponse().getContentAsString(), "$.sessionId");
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList("SELECT * FROM audit_event WHERE msisdn = ? ORDER BY id", MSISDN);
    }

    private List<Map<String, Object>> events(String event) {
        return jdbc.queryForList("SELECT * FROM audit_event WHERE msisdn = ? AND event = ? ORDER BY id", MSISDN, event);
    }

    private List<Object> eventNames() {
        return events().stream().map(row -> row.get("EVENT")).toList();
    }
}
