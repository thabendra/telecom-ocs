package com.thabendra.gatewaysim;

import java.util.UUID;

/**
 * What the gateway needs from the charging backend, independent of the protocol. The simulator only
 * talks to this interface; {@link RestChargingClient} and {@link SoapChargingClient} carry the calls,
 * and {@link RetryingChargingClient} adds the retry policy around either of them.
 * Failures are thrown as {@link BackendCallException}, the same for both protocols.
 */
public interface ChargingClient {

    /** Same shape as the backend's grant answer. */
    record Grant(String sessionId, String result, long grantedMb, Integer bandwidthKbps, String source,
                 String packageCode, Integer validitySeconds, String action) {

        public boolean allowed() {
            return "ALLOW".equals(result);
        }
    }

    record Release(String sessionId, long totalUsedMb) {
    }

    /** One answer plus whether the backend replayed a stored answer for this key. */
    record Answer<T>(T body, boolean replayed) {
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }

    Answer<Grant> start(String key, String msisdn, String app, long requestedMb);

    Answer<Grant> update(String key, String sessionId, long requestNumber, long usedMb, long requestedMb);

    Answer<Release> release(String key, String sessionId, long requestNumber, long usedMb);
}
