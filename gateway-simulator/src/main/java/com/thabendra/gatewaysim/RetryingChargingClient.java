package com.thabendra.gatewaysim;

/**
 * Adds the retry policy (section I) around any {@link ChargingClient}, REST or SOAP. Each retry calls
 * the wrapped client with exactly the same arguments, so the same Idempotency-Key and body (RT-7).
 */
public class RetryingChargingClient implements ChargingClient {

    private final ChargingClient delegate;
    private final RetryingCaller caller;

    public RetryingChargingClient(ChargingClient delegate, RetryingCaller caller) {
        this.delegate = delegate;
        this.caller = caller;
    }

    /** The protocol client underneath. */
    public ChargingClient delegate() {
        return delegate;
    }

    @Override
    public Answer<Grant> start(String key, String msisdn, String app, long requestedMb) {
        return caller.call(() -> delegate.start(key, msisdn, app, requestedMb));
    }

    @Override
    public Answer<Grant> update(String key, String sessionId, long requestNumber, long usedMb, long requestedMb) {
        return caller.call(() -> delegate.update(key, sessionId, requestNumber, usedMb, requestedMb));
    }

    @Override
    public Answer<Release> release(String key, String sessionId, long requestNumber, long usedMb) {
        return caller.call(() -> delegate.release(key, sessionId, requestNumber, usedMb));
    }
}
