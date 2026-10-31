package com.thabendra.gatewaysim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Client credentials against a mocked token server: the request, caching and refreshing (SE-2). */
class TokenClientTest {

    private static final String TOKEN_URL = "https://tokens/oauth2/token";

    private final FakeClock clock = new FakeClock();
    private MockRestServiceServer tokenServer;
    private TokenClient tokens;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        tokenServer = MockRestServiceServer.bindTo(builder).build();
        tokens = new TokenClient(builder.build(), TOKEN_URL, "pgw-colombo-01", "charging:write", clock);
    }

    @Test
    void asksForClientCredentialsWithItsIdAndScope() {
        tokenServer.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(allOf(containsString("grant_type=client_credentials"),
                        containsString("client_id=pgw-colombo-01"), containsString("scope=charging%3Awrite"))))
                .andRespond(token("t1", 300));

        assertThat(tokens.current()).isEqualTo("t1");
        tokenServer.verify();
    }

    @Test
    void reusesTheTokenUntilShortlyBeforeItExpires() {
        tokenServer.expect(once(), requestTo(TOKEN_URL)).andRespond(token("t1", 300));
        tokenServer.expect(once(), requestTo(TOKEN_URL)).andRespond(token("t2", 300));

        assertThat(tokens.current()).isEqualTo("t1");
        clock.advance(Duration.ofSeconds(269));                 // 300 - 30 s early refresh - 1
        assertThat(tokens.current()).isEqualTo("t1");          // still cached, no second call
        clock.advance(Duration.ofSeconds(1));
        assertThat(tokens.current()).isEqualTo("t2");          // refreshed 30 s before expiry

        tokenServer.verify();
    }

    @Test
    void invalidateFetchesANewToken() {
        tokenServer.expect(once(), requestTo(TOKEN_URL)).andRespond(token("t1", 300));
        tokenServer.expect(once(), requestTo(TOKEN_URL)).andRespond(token("t2", 300));

        tokens.current();
        tokens.invalidate();

        assertThat(tokens.current()).isEqualTo("t2");
    }

    @Test
    void refusedClientIsABackendCallError() {
        tokenServer.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_client\"}"));

        assertThatThrownBy(tokens::current)
                .isInstanceOfSatisfying(BackendCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e.getMessage()).contains("invalid_client");
                });
    }

    private static ResponseCreator token(String value, long expiresIn) {
        return withSuccess("""
                {"access_token":"%s","token_type":"Bearer","expires_in":%d,"scope":"charging:write"}"""
                .formatted(value, expiresIn), MediaType.APPLICATION_JSON);
    }

    /** A clock that only moves when the test says so. */
    private static final class FakeClock extends Clock {

        private Instant now = Instant.parse("2026-10-31T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
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
