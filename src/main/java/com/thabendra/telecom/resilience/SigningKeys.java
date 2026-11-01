package com.thabendra.telecom.resilience;

import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestOperations;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/**
 * CB-2: the public keys of one server that signs tokens (the token server, Keycloak). They change rarely,
 * so an outage of that server shouldn't stop us checking tokens. The default key cache forgets its keys
 * after 15 minutes, so a longer outage would refuse every token; this source keeps the last good keys
 * until it gets new ones:
 * <ul>
 *   <li>no keys yet: fetch them (the first token after start); if that fails the token can't be checked</li>
 *   <li>known key: answered from memory, with no call</li>
 *   <li>unknown key id: maybe a new key, so fetch, but only once per unknownKeyGap, so tokens with made-up
 *       key ids can't turn into a flood of calls to that server</li>
 *   <li>every refreshEvery: fetch; a failure just keeps the keys, a success drops any key removed at the
 *       server, so a retired key stops being trusted</li>
 * </ul>
 * Every fetch goes through the breaker: 3 failures in a row and nobody waits for the 2 s timeout any more.
 */
public class SigningKeys implements JWKSource<SecurityContext> {

    private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

    private final String name;
    private final String uri;
    private final RestOperations rest;
    private final CircuitBreaker breaker;
    private final Circuits circuits;
    private final Duration unknownKeyGap;
    private final Clock clock;

    private volatile JWKSet current;
    private volatile Instant lastUnknownKeyFetch = Instant.MIN;

    SigningKeys(String name, String uri, RestOperations rest, CircuitBreaker breaker, Circuits circuits,
                Duration unknownKeyGap, Clock clock) {
        this.name = name;
        this.uri = uri;
        this.rest = rest;
        this.breaker = breaker;
        this.circuits = circuits;
        this.unknownKeyGap = unknownKeyGap;
        this.clock = clock;
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) throws RemoteKeySourceException {
        JWKSet keys = current;
        if (keys == null) {
            return selector.select(firstKeys());
        }
        List<JWK> found = selector.select(keys);
        if (found.isEmpty() && mayLookForANewKey()) {
            try {
                return selector.select(fetch());
            } catch (RemoteKeySourceException e) {
                log.warn("signing keys of {}: could not look for a new key: {}", name, e.getMessage());
            }
        }
        return found;
    }

    /** Reads the keys again. On failure the keys we have stay in use. */
    public void refresh() {
        try {
            fetch();
        } catch (RemoteKeySourceException e) {
            log.warn("signing keys of {}: refresh failed, keeping the last good keys: {}", name, e.getMessage());
        }
    }

    private JWKSet firstKeys() throws RemoteKeySourceException {
        synchronized (this) {   // many tokens at once after a start: one fetch is enough
            return current != null ? current : fetch();
        }
    }

    private boolean mayLookForANewKey() {
        Instant now = clock.instant();
        synchronized (this) {
            if (Duration.between(lastUnknownKeyFetch, now).compareTo(unknownKeyGap) < 0) {
                return false;
            }
            lastUnknownKeyFetch = now;
            return true;
        }
    }

    private JWKSet fetch() throws RemoteKeySourceException {
        if (!breaker.tryAcquirePermission()) {
            circuits.rejected(name);
            throw new RemoteKeySourceException("Circuit of " + name + " is open, keys not fetched", null);
        }
        long began = System.nanoTime();
        try {
            JWKSet keys = JWKSet.parse(rest.getForObject(uri, String.class));
            breaker.onSuccess(System.nanoTime() - began, TimeUnit.NANOSECONDS);
            current = keys;
            return keys;
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().is5xxServerError()) {
                breaker.onError(System.nanoTime() - began, TimeUnit.NANOSECONDS, e);
            } else {
                breaker.onSuccess(System.nanoTime() - began, TimeUnit.NANOSECONDS);   // it answered
            }
            throw new RemoteKeySourceException("Keys of " + name + ": HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException | ParseException | IllegalArgumentException | CancellationException e) {
            // CancellationException: the JDK HTTP client's timeout, which RestTemplate doesn't wrap
            breaker.onError(System.nanoTime() - began, TimeUnit.NANOSECONDS, e);
            throw new RemoteKeySourceException("Keys of " + name + " not available: " + e.getMessage(), e);
        }
    }
}
