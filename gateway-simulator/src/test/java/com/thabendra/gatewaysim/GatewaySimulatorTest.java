package com.thabendra.gatewaysim;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
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
    void theWholeSessionIsOneTrace() {   // OB-3: every call of the session is inside this observation
        expect(START, ALLOW_100, false);
        expect(RELEASE, released(37), false);
        TestObservationRegistry observations = TestObservationRegistry.create();

        new GatewaySimulator(chargingClient(SimulatorProperties.Protocol.REST, 0, false),
                properties(0, false, SimulatorProperties.Protocol.REST), observations).run();

        backend.verify();
        TestObservationRegistryAssert.assertThat(observations)
                .hasObservationWithNameEqualTo("gateway.session").that()
                .hasBeenStarted()
                .hasBeenStopped()
                .hasLowCardinalityKeyValue("protocol", "REST")
                .hasLowCardinalityKeyValue("app", "YOUTUBE");
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

    @Test
    void backendDownFailsOpenAfterRetries() {
        for (int i = 0; i <= RetryPolicy.defaults().maxRetries(); i++) {
            backend.expect(requestTo("http://backend" + START))
                    .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        GatewaySimulator.Outcome outcome = simulator(5, false).run();

        backend.verify();
        assertThat(outcome.failedOpen()).isTrue();   // RL-6: traffic is let through, not blocked
        assertThat(outcome.blocked()).isFalse();
    }

    @Test
    void sameSessionFlowWorksOverSoap() {
        expectSoap("StartSessionRequest", soapGrant("StartSessionResponse", "ALLOW", 100),
                "<s:msisdn>94771234567</s:msisdn>", "<s:app>YOUTUBE</s:app>");
        expectSoap("UpdateSessionRequest", soapGrant("UpdateSessionResponse", "DENY", 0),
                "<s:requestNumber>1</s:requestNumber>", "<s:usedMb>100</s:usedMb>");
        expectSoap("ReleaseSessionRequest", """
                <s:ReleaseSessionResponse xmlns:s="http://thabendra.com/telecom/subscriber">
                  <s:sessionId>%s</s:sessionId><s:totalUsedMb>100</s:totalUsedMb>
                </s:ReleaseSessionResponse>""".formatted(SESSION),
                "<s:requestNumber>2</s:requestNumber>", "<s:usedMb>0</s:usedMb>");

        GatewaySimulator.Outcome outcome = simulator(5, false, SimulatorProperties.Protocol.SOAP).run();

        backend.verify();
        assertThat(outcome.blocked()).isTrue();
        assertThat(outcome.totalUsedMb()).isEqualTo(100);
    }

    private GatewaySimulator simulator(int maxUpdates, boolean replay) {
        return simulator(maxUpdates, replay, SimulatorProperties.Protocol.REST);
    }

    // built the same way as the app: ChargingClients picks the protocol, the simulator only sees the interface
    private GatewaySimulator simulator(int maxUpdates, boolean replay, SimulatorProperties.Protocol protocol) {
        return new GatewaySimulator(chargingClient(protocol, maxUpdates, replay),
                properties(maxUpdates, replay, protocol));
    }

    private ChargingClient chargingClient(SimulatorProperties.Protocol protocol, int maxUpdates, boolean replay) {
        SimulatorProperties props = properties(maxUpdates, replay, protocol);
        RetryingCaller caller = new RetryingCaller(props.retry(), wait -> { }, Clock.systemUTC(),
                new SimpleMeterRegistry());
        return ChargingClients.create(props, builder, caller);
    }

    private static SimulatorProperties properties(int maxUpdates, boolean replay,
                                                  SimulatorProperties.Protocol protocol) {
        return new SimulatorProperties("http://backend", "pgw-test-01", protocol,
                "94771234567", "YOUTUBE", 100, maxUpdates, 100, 37, replay, RetryPolicy.defaults());
    }

    private void expectSoap(String operation, String responsePayload, String... mustContain) {
        ResponseActions call = backend.expect(requestTo("http://backend/ws"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("<s:" + operation + ">")));
        for (String part : mustContain) {
            call.andExpect(content().string(containsString(part)));
        }
        call.andRespond(withSuccess("""
                <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/">
                  <SOAP-ENV:Body>%s</SOAP-ENV:Body>
                </SOAP-ENV:Envelope>""".formatted(responsePayload), MediaType.TEXT_XML));
    }

    private static String soapGrant(String response, String result, long grantedMb) {
        String extra = "ALLOW".equals(result)
                ? "<s:bandwidthKbps>2048</s:bandwidthKbps><s:source>PACKAGE</s:source>"
                + "<s:packageCode>YT_UNLIMITED</s:packageCode><s:validitySeconds>3600</s:validitySeconds>"
                : "<s:action>BLOCK</s:action>";
        return """
                <s:%s xmlns:s="http://thabendra.com/telecom/subscriber"><s:grant>
                  <s:sessionId>%s</s:sessionId><s:result>%s</s:result><s:grantedMb>%d</s:grantedMb>%s
                </s:grant></s:%s>""".formatted(response, SESSION, result, grantedMb, extra, response);
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
