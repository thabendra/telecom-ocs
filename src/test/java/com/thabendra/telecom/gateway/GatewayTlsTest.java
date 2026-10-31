package com.thabendra.telecom.gateway;

import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.idempotency.IdempotencyFilter;
import com.thabendra.telecom.soap.WebServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SE-1 over real TLS: the mTLS gateway port and the plain public port, with the test certificates
 * from scripts/generate-dev-certs.sh. Handshake failures surface as ResourceAccessException
 * (no HTTP answer at all). Skipped when Docker isn't running.
 */
@IntegrationTest
class GatewayTlsTest {

    private static final String MSISDN = "94771234567";
    private static final String START_BODY = "{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":100}".formatted(MSISDN);

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private ServerPorts ports;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        TestData.clean(jdbc, redis);
        ResponseEntity<String> created = TestCerts.exchange(TestCerts.http(ports.publicPort()).post()
                .uri("/api/subscribers")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                         "planCode":"BASIC","initialBalance":100.00}""".formatted(MSISDN)));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void allowedGatewayCertificateCanStartASessionOverRest() {
        ResponseEntity<String> answer = startOverRest(TestCerts.https(gatewayPort, TestCerts.COLOMBO));

        assertThat(answer.getStatusCode().value()).isEqualTo(201);
        assertThat(answer.getBody()).contains("\"result\":\"ALLOW\"");
    }

    @Test
    void allowedGatewayCertificateCanStartASessionOverSoap() {
        ResponseEntity<String> answer = TestCerts.exchange(TestCerts.https(gatewayPort, TestCerts.KANDY).post()
                .uri("/ws")
                .contentType(MediaType.TEXT_XML)
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .body("""
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:s="%s">
                          <soapenv:Body><s:StartSessionRequest>
                            <s:msisdn>%s</s:msisdn><s:app>GENERAL</s:app><s:requestedMb>100</s:requestedMb>
                          </s:StartSessionRequest></soapenv:Body>
                        </soapenv:Envelope>""".formatted(WebServiceConfig.NAMESPACE, MSISDN)));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).contains("StartSessionResponse", "ALLOW");
    }

    @Test
    void noClientCertificateFailsTheHandshake() {
        assertThatThrownBy(() -> startOverRest(TestCerts.https(gatewayPort, null)))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(sessions()).isZero();
    }

    @Test
    void certificateFromAnotherCaFailsTheHandshake() {
        // same name as a real gateway (CN=pgw-colombo-01), but not printed by our CA
        assertThatThrownBy(() -> startOverRest(TestCerts.https(gatewayPort, "stranger")))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(sessions()).isZero();
    }

    @Test
    void expiredCertificateFailsTheHandshake() {
        assertThatThrownBy(() -> startOverRest(TestCerts.https(gatewayPort, "pgw-expired")))
                .isInstanceOf(ResourceAccessException.class);
        assertThat(sessions()).isZero();
    }

    @Test
    void validCertificateOfAGatewayNotOnTheListIsForbidden() {
        ResponseEntity<String> answer = startOverRest(TestCerts.https(gatewayPort, "pgw-unknown-99"));

        assertThat(answer.getStatusCode().value()).isEqualTo(403);
        assertThat(sessions()).isZero();
    }

    @Test
    void chargingIsNotOnThePublicPort() {
        ResponseEntity<String> answer = startOverRest(TestCerts.http(ports.publicPort()));

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
        assertThat(sessions()).isZero();
    }

    @Test
    void soapIsNotOnThePublicPort() {
        ResponseEntity<String> answer = TestCerts.exchange(TestCerts.http(ports.publicPort()).get()
                .uri("/ws/subscribers.wsdl"));

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void publicApiIsNotOnTheGatewayPort() {
        ResponseEntity<String> answer = TestCerts.exchange(TestCerts.https(gatewayPort, TestCerts.COLOMBO).get()
                .uri("/api/plans"));

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void publicApiWorksOnThePublicPort() {
        ResponseEntity<String> answer = TestCerts.exchange(TestCerts.http(ports.publicPort()).get().uri("/api/plans"));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
    }

    private static ResponseEntity<String> startOverRest(RestClient client) {
        return TestCerts.exchange(client.post()
                .uri("/charging/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .body(START_BODY));
    }

    private long sessions() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM charging_session", Long.class);
    }
}
