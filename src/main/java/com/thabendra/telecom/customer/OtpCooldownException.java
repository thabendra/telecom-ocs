package com.thabendra.telecom.customer;

/** A new code was asked for too soon after the last one (429 + Retry-After). */
public class OtpCooldownException extends RuntimeException {

    private final long retryAfterSeconds;

    public OtpCooldownException(long retryAfterSeconds) {
        super("A code was sent a moment ago. Ask again after the Retry-After seconds.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
