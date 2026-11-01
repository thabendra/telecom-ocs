package com.thabendra.telecom.resilience;

import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The Redis breaker is open: the call was not sent. It is a DataAccessException on purpose: every caller
 * already has a fallback for "Redis failed" (local rate limit, the Oracle check, a normal grant), and
 * they now take it at once instead of after a timeout.
 */
public class RedisCircuitOpenException extends DataAccessResourceFailureException {

    public RedisCircuitOpenException() {
        super("Redis circuit is open, call not sent");
    }

    // thrown for every request while open, and it always means the same: no stack trace to build
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
