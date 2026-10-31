package com.thabendra.telecom.push;

/**
 * What a gateway must do for one subscriber (PU-1, PU-2).
 *
 * @param id            unique; the gateway uses it to apply a redelivered command only once
 * @param bandwidthKbps UNBLOCK only: the speed to allow again
 * @param attempt       which delivery this is (1 = first)
 */
public record GatewayCommand(String id, String msisdn, Action action, Reason reason, Integer bandwidthKbps,
                             int attempt) {

    public enum Action { BLOCK, UNBLOCK }

    public enum Reason {
        /** BLOCK: a charging DENY, nothing left anywhere */
        OUT_OF_QUOTA,
        /** BLOCK: suspended by the operator */
        SUSPENDED,
        /** BLOCK: fraud detected */
        FRAUD,
        /** UNBLOCK: topped up after running out */
        TOP_UP,
        /** UNBLOCK: bought a package after running out */
        PACKAGE,
        /** UNBLOCK: the suspension was lifted */
        UNSUSPENDED
    }
}
