package com.thabendra.telecom.resilience;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestOperations;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Builds the token decoders that read their keys through {@link SigningKeys} (CB-2): one breaker per
 * server that signs tokens, so the token server being down says nothing about Keycloak.
 */
@Component
public class SigningKeysFactory {

    private final Circuits circuits;
    private final CircuitProperties.SigningKeys properties;
    private final List<SigningKeys> all = new CopyOnWriteArrayList<>();

    public SigningKeysFactory(Circuits circuits, CircuitProperties properties) {
        this.circuits = circuits;
        this.properties = properties.signingKeys();
    }

    /**
     * @param name the breaker's name, e.g. token-server
     * @param rest how to reach that server (its TLS settings); the fetch timeouts are added here
     */
    public NimbusJwtDecoder decoder(String name, String jwkSetUri, RestTemplateBuilder rest) {
        RestOperations client = rest.connectTimeout(properties.timeout()).readTimeout(properties.timeout()).build();
        SigningKeys keys = keys(name, jwkSetUri, client);
        // Spring's builder keeps its other settings; only where the keys come from is replaced
        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .restOperations(client)
                .jwtProcessorCustomizer(processor -> processor.setJWSKeySelector(
                        new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys)))
                .build();
    }

    SigningKeys keys(String name, String jwkSetUri, RestOperations client) {
        // all of the last N calls failed = N failures in a row
        CircuitBreaker breaker = circuits.create(name, CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.failuresToOpen())
                .minimumNumberOfCalls(properties.failuresToOpen())
                .failureRateThreshold(100)
                .waitDurationInOpenState(properties.openFor())
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
        SigningKeys keys = new SigningKeys(name, jwkSetUri, client, breaker, circuits, properties.unknownKeyGap(),
                Clock.systemUTC());
        all.add(keys);
        return keys;
    }

    public void refreshAll() {
        all.forEach(SigningKeys::refresh);
    }

    /** Reads the keys again on a schedule, unless telecom.circuit.signing-keys.refresh is false. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "telecom.circuit.signing-keys.refresh", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final SigningKeysFactory factory;

        Schedule(SigningKeysFactory factory) {
            this.factory = factory;
        }

        @Scheduled(initialDelayString = "${telecom.circuit.signing-keys.refresh-every}",
                fixedDelayString = "${telecom.circuit.signing-keys.refresh-every}")
        void run() {
            factory.refreshAll();
        }
    }
}
