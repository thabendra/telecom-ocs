package com.thabendra.telecom.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * CB-3: the breakers' states in /actuator/health, as "circuit". Always UP, even with a breaker open: an
 * open breaker means this copy is working on its fallback, so restarting it would help nobody. The redis
 * entry of the same health page says whether Redis itself answers.
 */
@Component
public class CircuitHealthIndicator implements HealthIndicator {

    private final Circuits circuits;

    public CircuitHealthIndicator(Circuits circuits) {
        this.circuits = circuits;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up();
        for (CircuitBreaker breaker : circuits.all()) {
            health.withDetail(breaker.getName(), breaker.getState().name());
        }
        return health.build();
    }
}
