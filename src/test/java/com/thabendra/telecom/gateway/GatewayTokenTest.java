package com.thabendra.telecom.gateway;

import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import com.thabendra.telecom.TestData;
import com.thabendra.telecom.TestUsers;
import com.thabendra.telecom.TestTokens;
import com.thabendra.telecom.idempotency.IdempotencyFilter;
import com.thabendra.telecom.soap.WebServiceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.thabendra.telecom.TestCerts.COLOMBO;
import static com.thabendra.telecom.TestCerts.KANDY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SE-3 / SE-4 / SE-5 over real mTLS: every gateway call needs an access token that is genuine
 * (signature, iss, aud, exp), has scope charging:write, was issued to the same gateway as the
 * certificate, and is bound to that certificate. Tokens come from TestTokens (signed with the test
 * key the test context trusts). Skipped when Docker isn't running.
 */
@IntegrationTest
class GatewayTokenTest {

    private static final String MSISDN = "94771234567";

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private ServerPorts ports;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void setUp() throws Exception {
        TestData.clean(jdbc, redis);
        // the subscriber is test data: created as staff on the public port (group 6)
        mvc.perform(post("/api/subscribers").with(TestUsers.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"msisdn":"%s","fullName":"Kamal Perera","nic":"200012345678",
                         "planCode":"BASIC","initialBalance":100.00}""".formatted(MSISDN)))
                .andExpect(status().isCreated());
    }

    @Test
    void validTokenIsAccepted() {
        assertThat(start(TestTokens.token(COLOMBO)).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void noTokenIsUnauthorized() {
        ResponseEntity<String> answer = start(null);

        assertThat(answer.getStatusCode().value()).isEqualTo(401);
        assertThat(answer.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(sessions()).isZero();
    }

    @Test
    void expiredTokenIsUnauthorized() {   // beyond the 60 s clock skew
        Instant longAgo = Instant.now().minusSeconds(600);
        String expired = TestTokens.token(COLOMBO, claims -> claims
                .issuedAt(longAgo).notBefore(longAgo).expiresAt(longAgo.plusSeconds(300)));

        assertThat(start(expired).getStatusCode().value()).isEqualTo(401);
        assertThat(sessions()).isZero();
    }

    @Test
    void tokenForAnotherApiIsUnauthorized() {   // aud
        String wrongAudience = TestTokens.token(COLOMBO, claims -> claims.audience(List.of("billing-api")));

        assertThat(start(wrongAudience).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void tokenFromAnotherIssuerIsUnauthorized() {   // iss
        String wrongIssuer = TestTokens.token(COLOMBO, claims -> claims.issuer("https://evil.example"));

        assertThat(start(wrongIssuer).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void tokenSignedByAnotherKeyIsUnauthorized() {   // signature
        assertThat(start(TestTokens.tokenSignedByAnotherKey(COLOMBO)).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void tokenWithoutChargingScopeIsForbidden() {   // SE-5
        String readOnly = TestTokens.token(COLOMBO, claims -> claims.claim("scope", List.of("notify:read")));

        assertThat(start(readOnly).getStatusCode().value()).isEqualTo(403);
        assertThat(sessions()).isZero();
    }

    @Test
    void kandysTokenOverTheColomboConnectionIsUnauthorized() {   // bound to Kandy's certificate, so cnf fails
        ResponseEntity<String> answer = start(TestTokens.token(KANDY));

        assertThat(answer.getStatusCode().value()).isEqualTo(401);
        assertThat(sessions()).isZero();
    }

    @Test
    void tokenIssuedToAnotherGatewayIsForbidden() {   // cnf matches, but sub is another gateway
        String issuedToKandy = TestTokens.token(KANDY, claims -> claims.claim("cnf", TestTokens.boundTo(COLOMBO)));

        ResponseEntity<String> answer = start(issuedToKandy);

        assertThat(answer.getStatusCode().value()).isEqualTo(403);
        assertThat(answer.getBody()).contains("issued to pgw-kandy-01");
        assertThat(sessions()).isZero();
    }

    @Test
    void tokenBoundToAnotherCertificateIsUnauthorized() {   // SE-4: cnf mismatch, e.g. a stolen token
        String boundToKandy = TestTokens.token(COLOMBO, claims -> claims.claim("cnf", TestTokens.boundTo(KANDY)));

        ResponseEntity<String> answer = start(boundToKandy);

        // Spring Security's own cnf check (X509CertificateThumbprintValidator) refuses it
        assertThat(answer.getStatusCode().value()).isEqualTo(401);
        assertThat(answer.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
        assertThat(sessions()).isZero();
    }

    @Test
    void unboundTokenIsUnauthorized() {   // SE-4: no cnf at all
        String unbound = TestTokens.token(COLOMBO, claims -> claims.claims(all -> all.remove("cnf")));

        assertThat(start(unbound).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void soapNeedsATokenToo() {
        ResponseEntity<String> answer = TestCerts.exchange(TestCerts.https(gatewayPort, COLOMBO, null).post()
                .uri("/ws")
                .contentType(MediaType.TEXT_XML)
                .body("""
                        <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:s="%s">
                          <soapenv:Body><s:ListPlansRequest/></soapenv:Body>
                        </soapenv:Envelope>""".formatted(WebServiceConfig.NAMESPACE)));

        assertThat(answer.getStatusCode().value()).isEqualTo(401);
    }

    // a REST start session over pgw-colombo-01's certificate, with this token (null: none)
    private ResponseEntity<String> start(String token) {
        return TestCerts.exchange(TestCerts.https(gatewayPort, COLOMBO, token).post()
                .uri("/charging/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .header(IdempotencyFilter.KEY_HEADER, UUID.randomUUID().toString())
                .body("{\"msisdn\":\"%s\",\"app\":\"GENERAL\",\"requestedMb\":100}".formatted(MSISDN)));
    }

    private long sessions() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM charging_session", Long.class);
    }
}
