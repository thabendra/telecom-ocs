package com.thabendra.gatewaysim;

import org.springframework.http.HttpHeaders;

import java.util.function.Supplier;

/** Where the protocol clients get the Authorization: Bearer token from (group 3). */
public interface AccessTokens {

    /** No token server configured: calls go without an Authorization header. */
    AccessTokens NONE = new AccessTokens() {
        @Override
        public String current() {
            return null;
        }

        @Override
        public void invalidate() {
            // nothing cached
        }
    };

    /** A token that is still valid, fetched or refreshed when needed; null for no token. */
    String current();

    /** Forget the cached token, e.g. after the backend refused it. */
    void invalidate();

    /** Adds Authorization: Bearer when there is a token. */
    static void bearer(HttpHeaders headers, String token) {
        if (token != null) {
            headers.setBearerAuth(token);
        }
    }

    /**
     * Runs the call; if the backend answers 401 (token expired early, or the token server restarted
     * with a new key) it gets a fresh token once and sends the call again. This is separate from the
     * retry policy: a second 401 is final.
     */
    static <T> T refreshOnce(AccessTokens tokens, Supplier<T> call) {
        try {
            return call.get();
        } catch (BackendCallException e) {
            if (e.status() != 401 || tokens == NONE) {
                throw e;
            }
            tokens.invalidate();
            return call.get();
        }
    }
}
