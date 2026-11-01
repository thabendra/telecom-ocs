package com.thabendra.telecom.monitoring;

import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestUsers;
import com.thabendra.telecom.gateway.ServerPorts;
import com.thabendra.telecom.idempotency.IdempotencyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OB-2 over real HTTP: health and Prometheus metrics on the internal management port only, with the
 * business counters next to Spring's request metrics. Skipped when Docker isn't running.
 */
@IntegrationTest
class MetricsTest {

    private static final String MSISDN = "94771234567";

    @LocalManagementPort
    private int managementPort;

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private ServerPorts ports;

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
    void prometheusReadsTheBusinessNumbersOnTheManagementPort() throws Exception {
        mvc.perform(post("/api/subscribers").with(TestUsers.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                                 "planCode":"BASIC","initialBalance":0}""".formatted(MSISDN)))
                .andExpect(status().isCreated());
        mvc.perform(post("/charging/v1/sessions").with(TestCerts.gateway(TestCerts.COLOMBO))
                        .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":100}".formatted(MSISDN)))
                .andExpect(status().isCreated());

        ResponseEntity<String> metrics = TestCerts.exchange(TestCerts.http(managementPort).get()
                .uri("/actuator/prometheus"));

        assertThat(metrics.getStatusCode().value()).isEqualTo(200);
        assertThat(metrics.getBody())
                .contains("ocs_charging_decisions_total{")
                .contains("gateway=\"pgw-colombo-01\"")
                .contains("result=\"grant\"")
                .contains("ocs_bulkhead_free_slots")
                .contains("ocs_push_commands{")
                .contains("http_server_requests_seconds_bucket")   // the histogram behind p99
                .contains("application=\"telecom-ocs\"");
    }

    @Test
    void healthShowsOracleAndRedis() {
        ResponseEntity<String> health = TestCerts.exchange(TestCerts.http(managementPort).get().uri("/actuator/health"));

        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).contains("\"status\":\"UP\"", "\"db\"", "\"redis\"", "\"circuit\"");
    }

    @Test
    void theActuatorIsNotOnThePublicOrGatewayPort() {
        ResponseEntity<String> publicPort = TestCerts.exchange(TestCerts.http(ports.publicPort()).get()
                .uri("/actuator/prometheus"));
        ResponseEntity<String> gateway = TestCerts.exchange(TestCerts.https(gatewayPort, TestCerts.COLOMBO).get()
                .uri("/actuator/prometheus"));

        assertThat(publicPort.getStatusCode().value()).isIn(401, 404);
        assertThat(gateway.getStatusCode().value()).isEqualTo(404);
        assertThat(String.valueOf(publicPort.getBody())).doesNotContain("ocs_");
    }
}
