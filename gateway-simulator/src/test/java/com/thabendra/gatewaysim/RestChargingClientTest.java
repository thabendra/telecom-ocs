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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/** REST errors are turned into the protocol neutral BackendCallException the retry policy reads. */
class RestChargingClientTest {

    private static final String START = "http://backend/charging/v1/sessions";

    private MockRestServiceServer backend;
    private RestChargingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        backend = MockRestServiceServer.bindTo(builder).build();
        client = new RestChargingClient(builder, "http://backend", "pgw-test-01");
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
