package com.thabendra.gatewaysim;

import org.springframework.web.client.RestClient;

/** Picks the protocol client from simulator.protocol and wraps it with the retry policy. */
public final class ChargingClients {

    private ChargingClients() {
    }

    public static ChargingClient create(SimulatorProperties props, RestClient.Builder builder, RetryingCaller caller) {
        return create(props, builder, caller, AccessTokens.NONE);
    }

    /** With tokens, every call carries Authorization: Bearer (group 3). */
    public static ChargingClient create(SimulatorProperties props, RestClient.Builder builder, RetryingCaller caller,
                                        AccessTokens tokens) {
        ChargingClient transport = switch (props.protocol()) {
            case REST -> new RestChargingClient(builder, props.backendUrl(), props.clientId(), tokens);
            case SOAP -> new SoapChargingClient(builder, props.backendUrl(), props.clientId(), tokens);
        };
        return new RetryingChargingClient(transport, caller);
    }
}
