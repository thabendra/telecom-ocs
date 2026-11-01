package com.thabendra.telecom.gateway;

import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestUsers;
import com.thabendra.telecom.idempotency.IdempotencyFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import static com.thabendra.telecom.TestCerts.COLOMBO;
import static com.thabendra.telecom.TestCerts.KANDY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SE-6 against real Oracle + Redis: one gateway revoked, the other not touched, nothing restarted. */
@IntegrationTest
class RevokedGatewayTest {

    private static final String MSISDN = "94771234567";
    private static final Path LIVE = Path.of("target", "test-gateways.crl");
    private static final Path CERTS = Path.of("certs");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RevocationList revocations;

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        TestData.clean(jdbc, redis);
        mvc.perform(post("/api/subscribers").with(TestUsers.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                                 "planCode":"BASIC","initialBalance":0}""".formatted(MSISDN)))
                .andExpect(status().isCreated());
        use("gateways.crl");
    }

    // the other test classes share this list
    @AfterEach
    void restore() throws Exception {
        use("gateways.crl");
    }

    @Test
    void aRevokedGatewayIsRefusedEvenWithAValidTokenAndTheOtherGoesOn() throws Exception {
        start(COLOMBO).andExpect(status().isCreated());

        use("test-crls/colombo-revoked.crl");

        start(COLOMBO).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("The certificate of " + COLOMBO + " is revoked"));
        start(KANDY).andExpect(status().isCreated());
    }

    @Test
    void takingItOffTheListLetsItBackIn() throws Exception {
        use("test-crls/colombo-revoked.crl");
        start(COLOMBO).andExpect(status().isForbidden());

        use("gateways.crl");

        start(COLOMBO).andExpect(status().isCreated());
    }

    @Test
    void itIsRefusedOverRealMtlsToo() throws Exception {
        use("test-crls/colombo-revoked.crl");

        ResponseEntity<String> answer = TestCerts.exchange(TestCerts.https(port, COLOMBO).post()
                .uri("/charging/v1/sessions")
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":10}".formatted(MSISDN)));

        assertThat(answer.getStatusCode().value()).isEqualTo(403);
    }

    private ResultActions start(String gateway) throws Exception {
        return mvc.perform(post("/charging/v1/sessions").with(TestCerts.gateway(gateway))
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":10}".formatted(MSISDN)));
    }

    private void use(String file) throws Exception {
        Files.copy(CERTS.resolve(file), LIVE, StandardCopyOption.REPLACE_EXISTING);
        revocations.reload();
    }
}
