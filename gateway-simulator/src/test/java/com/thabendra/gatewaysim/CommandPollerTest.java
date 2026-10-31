package com.thabendra.gatewaysim;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The gateway side of the push channel (PU-3, PU-4) against a mocked backend. */
class CommandPollerTest {

    private static final String COMMANDS = "http://backend/charging/v1/commands";
    private static final String BLOCK = """
            {"id":"c-1","msisdn":"94771234567","action":"BLOCK","reason":"OUT_OF_QUOTA","attempt":1}""";
    private static final String UNBLOCK = """
            {"id":"c-2","msisdn":"94771234567","action":"UNBLOCK","reason":"TOP_UP","bandwidthKbps":1024,"attempt":1}""";

    private MockRestServiceServer backend;
    private CommandPoller poller;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        backend = MockRestServiceServer.bindTo(builder).build();
        AccessTokens token = new AccessTokens() {
            @Override
            public String current() {
                return "token-1";
            }

            @Override
            public void invalidate() {
            }
        };
        RetryingCaller caller = new RetryingCaller(RetryPolicy.defaults(), wait -> { }, Clock.systemUTC(),
                new SimpleMeterRegistry());
        poller = new CommandPoller(new CommandClient(builder, "http://backend", token), caller);
    }

    @Test
    void appliesEachCommandInOrderAndAcksIt() {
        expectPoll(json("[" + BLOCK + "," + UNBLOCK + "]"));
        expectAck("c-1", withNoContent());
        expectAck("c-2", withNoContent());

        CommandPoller.Result result = poller.pollOnce();

        backend.verify();
        assertThat(result).isEqualTo(new CommandPoller.Result(2, 2, 2));
        assertThat(poller.isBlocked("94771234567")).isFalse();          // BLOCK, then UNBLOCK
        assertThat(poller.allowedKbps("94771234567")).isEqualTo(1024);
    }

    @Test
    void aFailedPollIsTriedAgainNextTimeNotACrash() {   // the backend keeps the commands (PU-4)
        for (int i = 0; i < 4; i++) {   // the first try and 3 retries
            expectPoll(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        }

        CommandPoller.Result result = poller.pollOnce();

        backend.verify();
        assertThat(result).isEqualTo(new CommandPoller.Result(0, 0, 0));
    }

    @Test
    void blockStopsTheUsersTraffic() {
        expectPoll(json("[" + BLOCK + "]"));
        expectAck("c-1", withNoContent());

        poller.pollOnce();

        assertThat(poller.isBlocked("94771234567")).isTrue();
        assertThat(poller.allowedKbps("94771234567")).isNull();
    }

    @Test
    void aCommandDeliveredAgainIsAppliedOnceButAckedAgain() {   // the first ack got lost
        expectPoll(json("[" + BLOCK + "]"));
        expectAck("c-1", withStatus(HttpStatus.BAD_REQUEST));   // ack refused, a final answer: not sent
        expectPoll(json("[" + BLOCK.replace("\"attempt\":1", "\"attempt\":2") + "]"));
        expectAck("c-1", withNoContent());

        CommandPoller.Result first = poller.pollOnce();
        CommandPoller.Result second = poller.pollOnce();

        backend.verify();
        assertThat(first).isEqualTo(new CommandPoller.Result(1, 1, 0));
        assertThat(second).isEqualTo(new CommandPoller.Result(1, 0, 1));
    }

    @Test
    void aBusyBackendIsRetriedWithTheRetryPolicy() {   // section I: 503 is retried
        backend.expect(requestTo(COMMANDS)).andExpect(method(GET)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectPoll(json("[]"));

        assertThat(poller.pollOnce()).isEqualTo(new CommandPoller.Result(0, 0, 0));
        backend.verify();
    }

    private void expectPoll(ResponseCreator response) {
        backend.expect(requestTo(COMMANDS))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andRespond(response);
    }

    private void expectAck(String id, ResponseCreator response) {
        backend.expect(requestTo(COMMANDS + "/" + id + "/ack"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer token-1"))
                .andRespond(response);
    }

    private static ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }
}
