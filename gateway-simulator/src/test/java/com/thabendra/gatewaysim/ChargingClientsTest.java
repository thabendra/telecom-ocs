package com.thabendra.gatewaysim;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/** simulator.protocol picks the client; either way it comes wrapped with the retry policy. */
class ChargingClientsTest {

    private final RetryingCaller caller = new RetryingCaller(RetryPolicy.defaults(), wait -> { }, Clock.systemUTC(),
            new SimpleMeterRegistry());

    @Test
    void restProtocolGivesRetryingRestClient() {
        ChargingClient client = ChargingClients.create(props(SimulatorProperties.Protocol.REST, false),
                RestClient.builder(), caller);

        assertThat(client).isInstanceOf(RetryingChargingClient.class);
        assertThat(((RetryingChargingClient) client).delegate()).isInstanceOf(RestChargingClient.class);
    }

    @Test
    void soapProtocolGivesRetryingSoapClient() {
        ChargingClient client = ChargingClients.create(props(SimulatorProperties.Protocol.SOAP, false),
                RestClient.builder(), caller);

        assertThat(((RetryingChargingClient) client).delegate()).isInstanceOf(SoapChargingClient.class);
    }

    @Test
    void replayModeIsAllowedOverSoap() {   // the backend replays SOAP charging calls too
        ChargingClient client = ChargingClients.create(props(SimulatorProperties.Protocol.SOAP, true),
                RestClient.builder(), caller);

        assertThat(((RetryingChargingClient) client).delegate()).isInstanceOf(SoapChargingClient.class);
    }

    private static SimulatorProperties props(SimulatorProperties.Protocol protocol, boolean replay) {
        return new SimulatorProperties("http://backend", "pgw-test-01", protocol, "94771234567", "YOUTUBE",
                100, 5, 100, 37, replay, RetryPolicy.defaults());
    }
}
