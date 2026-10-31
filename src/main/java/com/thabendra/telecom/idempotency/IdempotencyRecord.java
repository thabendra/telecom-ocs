package com.thabendra.telecom.idempotency;

/**
 * What Redis holds for one idempotency key (stored as JSON).
 * IN_PROGRESS: the first request is still running. DONE: its final response is saved for replay.
 *
 * @param requestHash SHA-256 of method + path + body, to spot a key reused for a different request
 */
public record IdempotencyRecord(State state, String requestHash, Integer status, String contentType, String body) {

    public enum State { IN_PROGRESS, DONE }

    static IdempotencyRecord inProgress(String requestHash) {
        return new IdempotencyRecord(State.IN_PROGRESS, requestHash, null, null, null);
    }

    static IdempotencyRecord done(String requestHash, int status, String contentType, String body) {
        return new IdempotencyRecord(State.DONE, requestHash, status, contentType, body);
    }
}
