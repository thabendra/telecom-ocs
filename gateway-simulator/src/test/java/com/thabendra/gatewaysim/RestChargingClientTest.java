package com.thabendra.gatewaysim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * REST errors are turned into the protocol neutral BackendCallException the retry policy reads, and the
 * access token is sent and refreshed once after a 401.
 */
class RestChargingClientTest {

    private static final String START = "http://backend/charging/v1/sessions";
    private static final String GRANT = """
            {"sessionId":"9070f899-b1c9-4840-ab19-ca9d471eb017","result":"ALLOW","grantedMb":100,
             "bandwidthKbps":2048,"source":"PLAN","validitySeconds":3600}""";

    private RestClient.Builder builder;
    private MockRestServiceServer backend;
    private RestChargingClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        backend = MockRestServiceServer.bindTo(builder).build();
        client = new RestChargingClient(builder, "http://backend", "pgw-test-01");
    }

    @Test
    void withoutATokenServerNoAuthorizationHeaderIsSent() {
        backend.expect(requestTo(START))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(GRANT, MediaType.APPLICATION_JSON));

        client.start("key-1", "94771234567", "YOUTUBE", 100);

        backend.verify();
    }

    @Test
    void sendsTheBearerTokenAndRefreshesItOnceAfter401() {
        CountingTokens tokens = new CountingTokens();
        RestChargingClient withTokens = new RestChargingClient(builder, "http://backend", "pgw-test-01", tokens);
        backend.expect(requestTo(START))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        backend.expect(requestTo(START))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-2"))
                .andRespond(withSuccess(GRANT, MediaType.APPLICATION_JSON));

        assertThat(withTokens.start("key-1", "94771234567", "YOUTUBE", 100).body().allowed()).isTrue();

        backend.verify();
        assertThat(tokens.invalidated).isEqualTo(1);
    }

    @Test
    void aSecond401IsFinal() {
        CountingTokens tokens = new CountingTokens();
        RestChargingClient withTokens = new RestChargingClient(builder, "http://backend", "pgw-test-01", tokens);
        backend.expect(requestTo(START)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        backend.expect(requestTo(START)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> withTokens.start("key-1", "94771234567", "YOUTUBE", 100))
                .isInstanceOfSatisfying(BackendCallException.class, e -> assertThat(e.status()).isEqualTo(401));

        backend.verify();
        assertThat(tokens.invalidated).isEqualTo(1);
        assertThat(RetryingCaller.isRetryable(new BackendCallException(401, null, "x", null))).isFalse();
    }

    /** token-1 until invalidated, then token-2, ... */
    private static final class CountingTokens implements AccessTokens {

        private int invalidated;

        @Override
        public String current() {
            return "token-" + (invalidated + 1);
        }

        @Override
        public void invalidate() {
            invalidated++;
        }
    }

    @Test
    void errorKeepsStatusRetryAfterAndBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "1");
        backend.expect(requestTo(START)).andRespond(withStatus(HttpStatus.CONFLICT).headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"detail\":\"still being processed\"}"));

        assertThatThrownBy(() -> client.start("key-1", "94771234567", "YOUTUBE", 100))
                .isInstanceOfSatisfying(BackendCallException.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(e.getMessage()).contains("still being processed");
                });
    }

    @Test
    void connectionRefusedIsNoAnswer() {
        backend.expect(requestTo(START)).andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> client.start("key-1", "94771234567", "YOUTUBE", 100))
                .isInstanceOfSatisfying(BackendCallException.class,
                        e -> assertThat(e.status()).isEqualTo(BackendCallException.NO_ANSWER));
    }
}
