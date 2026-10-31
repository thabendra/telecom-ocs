package com.thabendra.gatewaysim;

import org.springframework.web.client.RestClient;

/** Picks the protocol client from simulator.protocol and wraps it with the retry policy. */
public final class ChargingClients {

    private ChargingClients() {
    }

    public static ChargingClient create(SimulatorProperties props, RestClient.Builder builder, RetryingCaller caller) {
        ChargingClient transport = switch (props.protocol()) {
            case REST -> new RestChargingClient(builder, props.backendUrl(), props.clientId());
            case SOAP -> new SoapChargingClient(builder, props.backendUrl(), props.clientId());
        };
        return new RetryingChargingClient(transport, caller);
    }
}
