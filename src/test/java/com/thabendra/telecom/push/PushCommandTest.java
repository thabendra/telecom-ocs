package com.thabendra.telecom.push;

import com.jayway.jsonpath.JsonPath;
import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestTokens;
import com.thabendra.telecom.idempotency.IdempotencyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Group 5 against Oracle: BLOCK on DENY / suspension / fraud, UNBLOCK + speed after a top-up, a package
 * or lifting a suspension (PU-1, PU-2), polled and acked by the gateways (PU-3), and delivered again
 * until acked, at most 4 times (PU-4). Skipped when Docker isn't running.
 */
@IntegrationTest
class PushCommandTest {

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

    // PU-1

    @Test
    void denyQueuesOneBlockForEveryGatewayOnce() throws Exception {
        createSubscriber("0.00");
        runOutOfEverything();
        start(100);   // a second DENY of the same user

        assertThat(commands()).hasSize(2)
                .allSatisfy(command -> {
                    assertThat(command.get("ACTION")).isEqualTo("BLOCK");
                    assertThat(command.get("REASON")).isEqualTo("OUT_OF_QUOTA");
                    assertThat(command.get("STATUS")).isEqualTo("PENDING");
                })
                .extracting(command -> command.get("GATEWAY")).containsExactlyInAnyOrder(COLOMBO, KANDY);
    }

    @Test
    void suspensionAndFraudBlockTheUser() throws Exception {
        createSubscriber("100.00");

        mvc.perform(put("/api/subscribers/{msisdn}/suspension", MSISDN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"FRAUD\"}"))
                .andExpect(status().isOk());

        assertThat(commands()).hasSize(2).allSatisfy(command -> {
            assertThat(command.get("ACTION")).isEqualTo("BLOCK");
            assertThat(command.get("REASON")).isEqualTo("FRAUD");
        });
        assertThat(jdbc.queryForObject("SELECT status FROM subscriber WHERE msisdn = ?", String.class, MSISDN))
                .isEqualTo("SUSPENDED");
    }

    // PU-2

    @Test
    void topUpAfterRunningOutUnblocksAtThePayAsYouGoSpeed() throws Exception {
        createSubscriber("0.00");
        runOutOfEverything();

        topUp("50.00");

        assertThat(latest("UNBLOCK")).hasSize(2).allSatisfy(command -> {
            assertThat(command.get("REASON")).isEqualTo("TOP_UP");
            assertThat(((Number) command.get("BANDWIDTH_KBPS")).intValue()).isEqualTo(1024);
        });
    }

    @Test
    void packageAfterRunningOutUnblocksAtThePackageSpeed() throws Exception {
        createSubscriber("0.00");
        runOutOfEverything();
        jdbc.update("UPDATE subscriber SET balance = 1000 WHERE msisdn = ?", MSISDN);   // money without a top-up

        mvc.perform(post("/api/subscribers/{msisdn}/packages", MSISDN).contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .content("{\"packageCode\":\"YT_UNLIMITED\"}"))
                .andExpect(status().isCreated());

        assertThat(latest("UNBLOCK")).hasSize(2).allSatisfy(command -> {
            assertThat(command.get("REASON")).isEqualTo("PACKAGE");
            assertThat(((Number) command.get("BANDWIDTH_KBPS")).intValue()).isEqualTo(2048);
        });
    }

    @Test
    void topUpWithoutABlockSendsNothing() throws Exception {
        createSubscriber("100.00");

        topUp("50.00");

        assertThat(commands()).isEmpty();
    }

    @Test
    void topUpDoesNotLiftASuspensionButUnsuspendDoes() throws Exception {
        createSubscriber("100.00");
        mvc.perform(put("/api/subscribers/{msisdn}/suspension", MSISDN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SUSPENDED\"}"))
                .andExpect(status().isOk());

        topUp("50.00");
        assertThat(latest("UNBLOCK")).isEmpty();

        mvc.perform(delete("/api/subscribers/{msisdn}/suspension", MSISDN)).andExpect(status().isOk());
        assertThat(latest("UNBLOCK")).hasSize(2).allSatisfy(command -> {
            assertThat(command.get("REASON")).isEqualTo("UNSUSPENDED");
            assertThat(((Number) command.get("BANDWIDTH_KBPS")).intValue()).isEqualTo(10240);   // BASIC plan
        });
    }

    // PU-3

    @Test
    void eachGatewayPollsItsOwnDueCommandsOnce() throws Exception {
        createSubscriber("0.00");
        runOutOfEverything();

        List<Map<String, Object>> colombo = poll(COLOMBO);
        assertThat(colombo).hasSize(1);
        assertThat(colombo.get(0)).containsEntry("msisdn", MSISDN).containsEntry("action", "BLOCK")
                .containsEntry("reason", "OUT_OF_QUOTA").containsEntry("attempt", 1);

        assertThat(poll(COLOMBO)).isEmpty();          // delivered, not due again yet
        assertThat(poll(KANDY)).hasSize(1);           // Kandy has its own copy
        assertThat(statusOf((String) colombo.get(0).get("id"))).isEqualTo("SENT");
    }

    @Test
    void pollingNeedsTheNotifyScope() throws Exception {   // SE-5
        String chargingOnly = TestTokens.token(COLOMBO, claims -> claims.claim("scope", List.of("charging:write")));

        mvc.perform(get("/charging/v1/commands").with(TestCerts.gateway(COLOMBO, chargingOnly)))
                .andExpect(status().isForbidden());
    }

    // PU-4

    @Test
    void anAckedCommandIsNotDeliveredAgain() throws Exception {
        createSubscriber("0.00");
        runOutOfEverything();
        String id = (String) poll(COLOMBO).get(0).get("id");

        ack(COLOMBO, id).andExpect(status().isNoContent());
        ack(COLOMBO, id).andExpect(status().isNoContent());   // acking twice is fine
        makeDue();

        assertThat(poll(COLOMBO)).isEmpty();
        assertThat(statusOf(id)).isEqualTo("ACKED");
    }

    @Test
    void withoutAnAckItComesAgainWithALongerWait() throws Exception {
        createSubscriber("0.00");
        runOutOfEverything();
        String id = (String) poll(COLOMBO).get(0).get("id");
        makeDue();   // as if the 10 s ack timeout had passed

        List<Map<String, Object>> again = poll(COLOMBO);

        assertThat(again).hasSize(1);
        assertThat(again.get(0)).containsEntry("id", id).containsEntry("attempt", 2);
        // second wait: 2 x 10 s (+ up to 20 % jitter)
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM gateway_command
                 WHERE id = ? AND next_attempt_at > SYSTIMESTAMP + INTERVAL '15' SECOND""", Integer.class, id))
                .isEqualTo(1);
    }

    @Test
    void afterFourDeliveriesWithoutAnAckItIsFailed() throws Exception {
        createSubscriber("0.00");
        runOutOfEverything();
        String id = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            List<Map<String, Object>> delivered = poll(COLOMBO);
            assertThat(delivered).hasSize(1);
            assertThat(delivered.get(0)).containsEntry("attempt", attempt);
            id = (String) delivered.get(0).get("id");
            makeDue();
        }

        assertThat(poll(COLOMBO)).isEmpty();
        assertThat(statusOf(id)).isEqualTo("FAILED");
    }

    @Test
    void anotherGatewaysCommandCannotBeAcked() throws Exception {
        createSubscriber("0.00");
        runOutOfEverything();
        String kandysCommand = (String) poll(KANDY).get(0).get("id");

        ack(COLOMBO, kandysCommand).andExpect(status().isNotFound());
        assertThat(statusOf(kandysCommand)).isEqualTo("SENT");
    }

    private void createSubscriber(String balance) throws Exception {
        mvc.perform(post("/api/subscribers").contentType(MediaType.APPLICATION_JSON).content("""
                        {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                         "planCode":"BASIC","initialBalance":%s}""".formatted(MSISDN, balance)))
                .andExpect(status().isCreated());
    }

    // with no money: one session holds all plan data, the next start is DENY
    private void runOutOfEverything() throws Exception {
        start(10240);
        String deny = start(100);
        assertThat(JsonPath.<String>read(deny, "$.result")).isEqualTo("DENY");
    }

    private String start(long requestedMb) throws Exception {
        return mvc.perform(post("/charging/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                        .with(TestCerts.gateway(COLOMBO))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .content("{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":%d}".formatted(MSISDN, requestedMb)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private void topUp(String amount) throws Exception {
        mvc.perform(post("/api/subscribers/{msisdn}/top-ups", MSISDN).contentType(MediaType.APPLICATION_JSON)
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .content("{\"amount\":%s}".formatted(amount)))
                .andExpect(status().isOk());
    }

    private List<Map<String, Object>> poll(String gateway) throws Exception {
        String body = mvc.perform(get("/charging/v1/commands").with(TestCerts.gateway(gateway)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$");
    }

    private ResultActions ack(String gateway, String id) throws Exception {
        return mvc.perform(post("/charging/v1/commands/{id}/ack", id).with(TestCerts.gateway(gateway)));
    }

    private void makeDue() {
        jdbc.update("UPDATE gateway_command SET next_attempt_at = SYSTIMESTAMP - INTERVAL '1' SECOND");
    }

    private List<Map<String, Object>> commands() {
        return jdbc.queryForList("SELECT * FROM gateway_command WHERE msisdn = ? ORDER BY created_at", MSISDN);
    }

    private List<Map<String, Object>> latest(String action) {
        return jdbc.queryForList("SELECT * FROM gateway_command WHERE msisdn = ? AND action = ?", MSISDN, action);
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT status FROM gateway_command WHERE id = ?", String.class, id);
    }
}
