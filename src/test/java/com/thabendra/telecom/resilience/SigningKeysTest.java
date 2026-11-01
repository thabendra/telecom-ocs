package com.thabendra.telecom.resilience;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import com.thabendra.telecom.TestBreakers;
import com.thabendra.telecom.monitoring.OcsMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestOperations;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CB-2: the last good keys stay in use while the server that signs tokens is down, tokens with unknown
 * key ids can't flood it, a removed key stops being trusted, and fetches go through a breaker.
 */
class SigningKeysTest {

    private static final String URI = "http://keys.test/jwks";

    private static RSAKey keyOne;
    private static RSAKey keyTwo;

    private RestOperations rest;
    private MovableClock clock;
    private CircuitBreaker breaker;
    private SigningKeys keys;

    @BeforeAll
    static void makeKeys() throws JOSEException {
        keyOne = new RSAKeyGenerator(2048).keyID("key-1").generate();
        keyTwo = new RSAKeyGenerator(2048).keyID("key-2").generate();
    }

    @BeforeEach
    void setUp() {
        rest = mock(RestOperations.class);
        clock = new MovableClock();
        Circuits circuits = new Circuits(new OcsMetrics(new SimpleMeterRegistry()));
        breaker = circuits.create("token-server", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(3)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(100)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
        keys = new SigningKeys("token-server", URI, rest, breaker, circuits, Duration.ofSeconds(30), clock);
    }

    @Test
    void theFirstTokenFetchesTheKeysAndThenTheyComeFromMemory() throws Exception {
        serverHas(keyOne);

        assertThat(find("key-1")).hasSize(1);
        assertThat(find("key-1")).hasSize(1);

        verify(rest, times(1)).getForObject(URI, String.class);
    }

    @Test
    void theLastGoodKeysStayInUseWhileTheServerIsDown() throws Exception {
        serverHas(keyOne);
        find("key-1");
        serverIsDown();

        keys.refresh();   // however long the outage lasts

        assertThat(find("key-1")).hasSize(1);
    }

    @Test
    void withNoKeysYetAndTheServerDownATokenCannotBeChecked() {
        serverIsDown();

        assertThatThrownBy(() -> find("key-1")).isInstanceOf(RemoteKeySourceException.class);
    }

    @Test
    void anUnknownKeyIdMayBeANewKey() throws Exception {
        serverHas(keyOne);
        find("key-1");
        serverHas(keyOne, keyTwo);

        assertThat(find("key-2")).hasSize(1);
    }

    @Test
    void madeUpKeyIdsCannotFloodTheServer() throws Exception {
        serverHas(keyOne);
        find("key-1");

        for (int i = 0; i < 50; i++) {
            assertThat(find("made-up-" + i)).isEmpty();
        }
        verify(rest, times(2)).getForObject(URI, String.class);   // the first fetch + one look

        clock.forward(Duration.ofSeconds(31));
        find("made-up-again");
        verify(rest, times(3)).getForObject(URI, String.class);
    }

    @Test
    void aKeyRemovedAtTheServerStopsBeingTrusted() throws Exception {
        serverHas(keyOne, keyTwo);
        assertThat(find("key-1")).hasSize(1);
        serverHas(keyTwo);

        keys.refresh();

        assertThat(find("key-1")).isEmpty();
        assertThat(find("key-2")).hasSize(1);
    }

    @Test
    void threeFailuresInARowOpenTheBreakerAndCallsStop() {
        serverIsDown();

        for (int i = 0; i < 3; i++) {
            keys.refresh();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        keys.refresh();
        keys.refresh();
        verify(rest, times(3)).getForObject(URI, String.class);
    }

    @Test
    void aTimeoutOfTheJdkHttpClientCountsAsAFailureAndTheKeysStay() throws Exception {
        serverHas(keyOne);
        find("key-1");
        when(rest.getForObject(URI, String.class)).thenThrow(new CancellationException());   // not wrapped by RestTemplate

        for (int i = 0; i < 3; i++) {
            keys.refresh();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(find("key-1")).hasSize(1);
    }

    @Test
    void aServerThatAnswersWithAnErrorIsAlive() {   // 404: a wrong address, the breaker wouldn't help
        when(rest.getForObject(URI, String.class)).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        for (int i = 0; i < 3; i++) {
            keys.refresh();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // the real decoder over real HTTP, the way SecurityConfig and StaffTokens build it

    @Test
    void theDecoderKeepsCheckingTokensAfterTheServerIsGone() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        byte[] body = new JWKSet(keyOne).toPublicJWKSet().toString().getBytes(StandardCharsets.UTF_8);
        server.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String uri = "http://localhost:" + server.getAddress().getPort() + "/jwks";
        Circuits circuits = new Circuits(new OcsMetrics(new SimpleMeterRegistry()));
        SigningKeysFactory factory = new SigningKeysFactory(circuits, TestBreakers.properties());
        NimbusJwtDecoder decoder = factory.decoder("token-server", uri, new RestTemplateBuilder());
        String token = signedWith(keyOne);

        Jwt first = decoder.decode(token);
        server.stop(0);
        factory.refreshAll();   // fails, and the keys stay
        Jwt second = decoder.decode(token);

        assertThat(first.getSubject()).isEqualTo("pgw-colombo-01");
        assertThat(second.getSubject()).isEqualTo("pgw-colombo-01");
    }

    private static String signedWith(RSAKey key) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("pgw-colombo-01").issuedAt(now)
                .expiresAt(now.plusSeconds(300)).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private List<JWK> find(String keyId) throws RemoteKeySourceException {
        return keys.get(new JWKSelector(new JWKMatcher.Builder().keyID(keyId).build()), null);
    }

    private void serverHas(RSAKey... published) {
        List<JWK> list = List.of((JWK[]) published);
        when(rest.getForObject(URI, String.class)).thenReturn(new JWKSet(list).toPublicJWKSet().toString());
    }

    private void serverIsDown() {
        when(rest.getForObject(URI, String.class)).thenThrow(new ResourceAccessException("connection refused"));
    }

    /** A clock the test moves forward, so the unknown-key gap needs no waiting. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-01T00:00:00Z");

        void forward(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
