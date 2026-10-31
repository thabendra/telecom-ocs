package com.thabendra.gatewaysim;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The simulator against a mocked backend: request order, numbers and headers a gateway must send. */
class GatewaySimulatorTest {

    private static final String SESSION = "9070f899-b1c9-4840-ab19-ca9d471eb017";
    private static final String START = "/charging/v1/sessions";
    private static final String UPDATE = START + "/" + SESSION + "/update";
    private static final String RELEASE = START + "/" + SESSION + "/release";
    private static final String ALLOW_100 = """
            {"sessionId":"%s","result":"ALLOW","grantedMb":100,"bandwidthKbps":2048,
             "source":"PACKAGE","packageCode":"YT_UNLIMITED","validitySeconds":3600}""".formatted(SESSION);
    private static final String DENY = """
            {"sessionId":"%s","result":"DENY","grantedMb":0,"action":"BLOCK"}""".formatted(SESSION);

    private RestClient.Builder builder;
    private MockRestServiceServer backend;
    private final Set<String> keys = new HashSet<>();

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        backend = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    void runsStartUpdatesAndReleaseWithNumbersAndKeys() {
        expect(START, ALLOW_100, false,
                jsonPath("$.msisdn").value("94771234567"), jsonPath("$.app").value("YOUTUBE"));
        expect(UPDATE, ALLOW_100, false, jsonPath("$.requestNumber").value(1), jsonPath("$.usedMb").value(100));
        expect(UPDATE, ALLOW_100, false, jsonPath("$.requestNumber").value(2));
        expect(RELEASE, released(237), false, jsonPath("$.requestNumber").value(3), jsonPath("$.usedMb").value(37));

        GatewaySimulator.Outcome outcome = simulator(2, false).run();

        backend.verify();
        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.updates()).isEqualTo(2);
        assertThat(outcome.totalUsedMb()).isEqualTo(237);
        assertThat(keys).hasSize(4);   // a different Idempotency-Key for every request
    }

    @Test
    void denyOnUpdateBlocksAndReleasesWithZero() {
        expect(START, ALLOW_100, false);
        expect(UPDATE, DENY, false);
        expect(RELEASE, released(100), false, jsonPath("$.requestNumber").value(2), jsonPath("$.usedMb").value(0));

        GatewaySimulator.Outcome outcome = simulator(5, false).run();

        backend.verify();
        assertThat(outcome.blocked()).isTrue();
    }

    @Test
    void denyOnStartBlocksWithoutFurtherCalls() {
        expect(START, DENY, false);

        GatewaySimulator.Outcome outcome = simulator(5, false).run();

        backend.verify();
        assertThat(outcome.blocked()).isTrue();
        assertThat(outcome.updates()).isZero();
    }

    @Test
    void replayModeSendsEachRequestTwiceWithTheSameKey() {
        expect(START, ALLOW_100, false);
        expect(START, ALLOW_100, true);
        expect(UPDATE, DENY, false);
        expect(UPDATE, DENY, true);
        expect(RELEASE, released(100), false);
        expect(RELEASE, released(100), true);

        simulator(5, true).run();

        backend.verify();
        assertThat(keys).hasSize(3);   // 6 calls, but each retry reused its request's key
    }

    @Test
    void replayModeFailsWhenBackendDoesNotReplay() {
        expect(START, ALLOW_100, false);
        expect(START, ALLOW_100, false);   // answered again without Idempotent-Replayed

        assertThatThrownBy(() -> simulator(5, true).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was not replayed");
    }

    private GatewaySimulator simulator(int maxUpdates, boolean replay) {
        SimulatorProperties props = new SimulatorProperties("http://backend", "pgw-test-01", "94771234567",
                "YOUTUBE", 100, maxUpdates, 100, 37, replay);
        return new GatewaySimulator(new ChargingClient(builder, props.backendUrl(), props.clientId()), props);
    }

    // one expected call: common gateway headers + the given checks, answered with json
    private void expect(String path, String json, boolean replayed, RequestMatcher... checks) {
        ResponseActions call = backend.expect(requestTo("http://backend" + path))
                .andExpect(method(POST))
                .andExpect(header("X-Client-Id", "pgw-test-01"))
                .andExpect(request -> keys.add(request.getHeaders().getFirst("Idempotency-Key")));
        for (RequestMatcher check : checks) {
            call.andExpect(check);
        }
        HttpHeaders headers = new HttpHeaders();
        if (replayed) {
            headers.add("Idempotent-Replayed", "true");
        }
        call.andRespond(withSuccess(json, MediaType.APPLICATION_JSON).headers(headers));
    }

    private static String released(long totalUsedMb) {
        return "{\"sessionId\":\"%s\",\"totalUsedMb\":%d}".formatted(SESSION, totalUsedMb);
    }
}
