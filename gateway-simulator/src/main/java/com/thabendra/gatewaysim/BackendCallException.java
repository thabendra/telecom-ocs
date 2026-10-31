package com.thabendra.gatewaysim;

import java.time.Duration;

/**
 * A charging call that didn't give an answer, in protocol neutral terms, so the retry policy works
 * the same for REST and SOAP:
 * <ul>
 *   <li>status 0: no answer at all (timeout, connection refused)</li>
 *   <li>any other status: the HTTP status the backend answered with; a SOAP Client fault is 400,
 *       a SOAP Server fault is 500</li>
 * </ul>
 */
public class BackendCallException extends RuntimeException {

    public static final int NO_ANSWER = 0;

    private final int status;
    private final Duration retryAfter;

    public BackendCallException(int status, Duration retryAfter, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public static BackendCallException noAnswer(Throwable cause) {
        return new BackendCallException(NO_ANSWER, null, "No answer from backend: " + cause.getMessage(), cause);
    }

    public int status() {
        return status;
    }

    /** The backend's Retry-After, or null when it didn't send one. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** Retry-After in seconds; the HTTP-date form isn't used by this backend. */
    static Duration parseRetryAfter(String seconds) {
        if (seconds == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(seconds.trim()));
        } catch (NumberFormatException notSeconds) {
            return null;
        }
    }
}
