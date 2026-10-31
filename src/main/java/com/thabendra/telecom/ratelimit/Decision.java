package com.thabendra.telecom.ratelimit;

/**
 * The answer of a token bucket.
 *
 * @param allowed whether the request may go on (a token was taken)
 * @param waitMs  when refused: how long until one token is back
 */
public record Decision(boolean allowed, long waitMs) {

    public static Decision allow() {
        return new Decision(true, 0);
    }

    /** Retry-After is whole seconds (HTTP), at least 1. */
    public long retryAfterSeconds() {
        return Math.max(1, (waitMs + 999) / 1000);
    }
}
