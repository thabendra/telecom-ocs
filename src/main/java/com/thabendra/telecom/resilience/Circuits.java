package com.thabendra.telecom.resilience;

import com.thabendra.telecom.monitoring.OcsMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every circuit breaker of this backend copy. The breaker lives in the caller: it counts the results of
 * the calls we make, and Resilience4j moves it between the states:
 * <pre>
 *   CLOSED     all calls go through; results are counted
 *   OPEN       too many failures: calls fail at once and nothing is sent, until the wait is over
 *   HALF_OPEN  a few test calls decide: all fine -> CLOSED, otherwise OPEN with a new wait
 * </pre>
 * A breaker made here publishes its state as a metric (CB-3) and logs every change of state, so a
 * breaker that opens is logged once, not once per refused request.
 */
@Component
public class Circuits {

    private static final Logger log = LoggerFactory.getLogger(Circuits.class);

    private final OcsMetrics metrics;
    private final List<CircuitBreaker> all = new CopyOnWriteArrayList<>();

    public Circuits(OcsMetrics metrics) {
        this.metrics = metrics;
    }

    public CircuitBreaker create(String name, CircuitBreakerConfig config) {
        CircuitBreaker breaker = CircuitBreaker.of(name, config);
        breaker.getEventPublisher().onStateTransition(event ->
                log.warn("circuit {}: {}", name, event.getStateTransition()));
        metrics.circuit(name, breaker::getState);
        all.add(breaker);
        return breaker;
    }

    public List<CircuitBreaker> all() {
        return all;
    }

    public void rejected(String name) {
        metrics.circuitRejected(name);
    }
}
