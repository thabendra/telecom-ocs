package com.thabendra.tokenserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The token server over real mTLS with the test certificates from scripts/generate-dev-certs.sh
 * (tests run in token-server/, so the files are in ../certs).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"telecom.token.signing.directory=target/test-signing-keys",
                "telecom.token.revocation.location=file:target/test-gateways.crl"})
class TokenServerTest {

    private static final Path CERTS = Path.of("..", "certs");
    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private RevokedCertificateFilter revocations;

    @Autowired
    private SigningKeyStore signingKeys;

    @BeforeEach
    void nobodyIsRevoked() throws Exception {
        useRevocationList("gateways.crl");
    }

    @Test
    void gatewayGetsACertificateBoundTokenForTheChargingApi() throws Exception {   // SE-2, SE-4, SE-5
        ResponseEntity<String> answer = token("pgw-colombo-01", "pgw-colombo-01", "charging:write");

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        JsonNode body = JSON.readTree(answer.getBody());
        assertThat(body.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(body.get("expires_in").asLong()).isBetween(295L, 300L);   // 5 minutes

        JsonNode claims = claims(body.get("access_token").asText());
        assertThat(claims.get("sub").asText()).isEqualTo("pgw-colombo-01");
        assertThat(claims.get("iss").asText()).isEqualTo("https://localhost:9443");
        assertThat(claims.get("aud").toString()).contains("charging-api");
        assertThat(claims.get("scope").toString()).contains("charging:write").doesNotContain("notify:read");
        assertThat(claims.get("cnf").get("x5t#S256").asText()).isEqualTo(thumbprint("pgw-colombo-01"));
    }

    @Test
    void certificateOfAnotherGatewayCannotGetItsToken() {   // tls_client_auth: subject must match client_id
        ResponseEntity<String> answer = token("pgw-colombo-01", "pgw-kandy-01", "charging:write");

        assertThat(answer.getStatusCode().value()).isEqualTo(401);
        assertThat(answer.getBody()).contains("invalid_client");
    }

    @Test
    void noCertificateGetsNoToken() {
        ResponseEntity<String> answer = token(null, "pgw-colombo-01", "charging:write");

        assertThat(answer.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void unknownClientGetsNoToken() {   // a valid certificate from our CA, but no registered client
        ResponseEntity<String> answer = token("pgw-unknown-99", "pgw-unknown-99", "charging:write");

        assertThat(answer.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void scopeTheClientDoesNotHaveIsRefused() {   // SE-5
        ResponseEntity<String> answer = token("pgw-colombo-01", "pgw-colombo-01", "admin:write");

        assertThat(answer.getStatusCode().value()).isEqualTo(400);
        assertThat(answer.getBody()).contains("invalid_scope");
    }

    @Test
    void publicKeysAreReadableWithoutACertificate() {   // SE-3: the backend reads them to check signatures
        ResponseEntity<String> answer = exchange(https(null).get().uri("/oauth2/jwks"));

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).contains("\"keys\"", "\"kty\":\"RSA\"").doesNotContain("\"d\"");
    }

    @Test
    void theTokenNamesTheSigningKeyAndThatKeyIsPublished() throws Exception {   // SE-7
        String jwt = JSON.readTree(token("pgw-colombo-01", "pgw-colombo-01", "charging:write").getBody())
                .get("access_token").asText();

        String keyId = JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[0])).get("kid").asText();

        assertThat(keyId).isEqualTo(signingKeys.signingKeyId());
        assertThat(exchange(https(null).get().uri("/oauth2/jwks")).getBody()).contains(keyId);
    }

    @Test
    void aRevokedGatewayGetsNoTokenAndTheOtherStillDoes() throws Exception {   // SE-6
        useRevocationList("test-crls/colombo-revoked.crl");

        ResponseEntity<String> revoked = token("pgw-colombo-01", "pgw-colombo-01", "charging:write");
        assertThat(revoked.getStatusCode().value()).isEqualTo(401);
        assertThat(revoked.getBody()).contains("invalid_client");
        assertThat(token("pgw-kandy-01", "pgw-kandy-01", "charging:write").getStatusCode().value()).isEqualTo(200);

        useRevocationList("gateways.crl");
        assertThat(token("pgw-colombo-01", "pgw-colombo-01", "charging:write").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aDisabledClientIsNotRegistered() {   // SE-6: whichever certificate it shows
        TokenProperties properties = new TokenProperties("https://localhost", "charging-api", Duration.ofMinutes(5),
                List.of(new TokenProperties.Client("pgw-off", "CN=pgw-off", List.of("charging:write"), false),
                        new TokenProperties.Client("pgw-on", "CN=pgw-on", List.of("charging:write"), true)),
                null, null);

        RegisteredClientRepository clients = new AuthorizationServerConfig().gatewayClients(properties);

        assertThat(clients.findByClientId("pgw-off")).isNull();
        assertThat(clients.findByClientId("pgw-on")).isNotNull();
    }

    private void useRevocationList(String file) throws Exception {
        Files.copy(CERTS.resolve(file), Path.of("target", "test-gateways.crl"), StandardCopyOption.REPLACE_EXISTING);
        revocations.reload();
    }

    private ResponseEntity<String> token(String certificate, String clientId, String scope) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("scope", scope);
        return exchange(https(certificate).post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form));
    }

    private RestClient https(String certificate) {
        try {
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(load("gateway-ca-truststore"));
            KeyManagerFactory keys = null;
            if (certificate != null) {
                keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keys.init(load(certificate), PASSWORD);
            }
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(keys == null ? null : keys.getKeyManagers(), trust.getTrustManagers(), null);
            return RestClient.builder()
                    .baseUrl("https://localhost:" + port)
                    .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder().sslContext(tls).build()))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseEntity<String> exchange(RestClient.RequestHeadersSpec<?> request) {
        return request.retrieve().onStatus(status -> true, (req, res) -> { }).toEntity(String.class);
    }

    private static JsonNode claims(String jwt) throws Exception {
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    private static String thumbprint(String certificate) throws Exception {
        try (InputStream pem = Files.newInputStream(CERTS.resolve(certificate + ".pem"))) {
            byte[] der = CertificateFactory.getInstance("X.509").generateCertificate(pem).getEncoded();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(der));
        }
    }

    private static KeyStore load(String name) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(CERTS.resolve(name + ".p12"))) {
            store.load(in, PASSWORD);
        }
        return store;
    }
}
