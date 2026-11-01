package com.thabendra.telecom.monitoring;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * The system's own numbers (OB-2), read by Prometheus at /actuator/prometheus on the management port.
 * Requests per second and latency (p99) come from Spring's http.server.requests; these are the
 * business numbers next to them:
 * <pre>
 *   ocs_charging_decisions_total{operation, result, gateway}   GRANT / DENY per gateway
 *   ocs_ratelimit_rejected_total{api, caller}                   429s: gateway / ip / customer / staff
 *   ocs_bulkhead_rejected_total, ocs_bulkhead_free_slots        503s and free database slots (RL-4)
 *   ocs_load_shed_total                                         503s that keep slots for update / release (RL-5)
 *   ocs_otp_sent_total, ocs_otp_checked_total{result}          logins; many "wrong" = someone guessing
 *   ocs_push_commands{status}                                   PENDING / SENT / FAILED commands (group 5)
 *   ocs_sessions_expired_total                                  sessions a gateway never released (CH-8)
 *   ocs_heavy_users_flagged_total                               subscribers who became heavy users (RL-3)
 *   ocs_circuit_state{name, state}, ocs_circuit_rejected_total  circuit breakers: redis, key servers (CB-3)
 * </pre>
 * Tags only take values from small fixed sets (the allowed gateways, a few results), never a phone
 * number or IP: every tag value is a separate time series in Prometheus.
 */
@Component
public class OcsMetrics {

    private final MeterRegistry registry;

    public OcsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** operation start / update, result grant / deny. */
    public void chargingDecision(String operation, boolean granted, String gateway) {
        Counter.builder("ocs.charging.decisions")
                .description("Charging decisions for the gateways")
                .tag("operation", operation)
                .tag("result", granted ? "grant" : "deny")
                .tag("gateway", gateway)
                .register(registry).increment();
    }

    /** api gateway / public, caller gateway / ip / customer / staff. */
    public void rateLimited(String api, String caller) {
        Counter.builder("ocs.ratelimit.rejected")
                .description("Requests refused with 429 by a token bucket")
                .tag("api", api)
                .tag("caller", caller)
                .register(registry).increment();
    }

    public void bulkheadRejected() {
        Counter.builder("ocs.bulkhead.rejected")
                .description("Requests refused with 503: no free database slot")
                .register(registry).increment();
    }

    public void loadShed() {
        Counter.builder("ocs.load.shed")
                .description("Requests refused with 503 to keep database slots for update and release")
                .register(registry).increment();
    }

    public void bulkheadSlots(Supplier<Number> freeSlots) {
        Gauge.builder("ocs.bulkhead.free.slots", freeSlots)
                .description("Database slots free in this backend copy right now")
                .register(registry);
    }

    /** CB-3: one gauge per state, 1 for the state the breaker is in now, 0 for the others. */
    public void circuit(String name, Supplier<CircuitBreaker.State> state) {
        for (CircuitBreaker.State candidate : List.of(CircuitBreaker.State.CLOSED, CircuitBreaker.State.OPEN,
                CircuitBreaker.State.HALF_OPEN)) {
            Gauge.builder("ocs.circuit.state", () -> state.get() == candidate ? 1 : 0)
                    .description("Circuit breaker state: 1 for the current state")
                    .tag("name", name)
                    .tag("state", candidate.name().toLowerCase())
                    .register(registry);
        }
    }

    /** CB-3: calls refused at once by an open breaker. */
    public void circuitRejected(String name) {
        Counter.builder("ocs.circuit.rejected")
                .description("Calls not sent because the circuit breaker is open")
                .tag("name", name)
                .register(registry).increment();
    }

    /** RL-3: a subscriber just crossed the heavy-user threshold (an alert, never a block). */
    public void heavyUserFlagged() {
        Counter.builder("ocs.heavy.users.flagged")
                .description("Subscribers who crossed the heavy-user threshold")
                .register(registry).increment();
    }

    /** CH-8: sessions closed because their gateway stopped reporting. */
    public void sessionsExpired(int count) {
        Counter.builder("ocs.sessions.expired")
                .description("Charging sessions expired without a release")
                .register(registry).increment(count);
    }

    public void otpSent() {
        Counter.builder("ocs.otp.sent").description("Login codes sent by SMS").register(registry).increment();
    }

    /** result ok / wrong / locked / missing. */
    public void otpChecked(String result) {
        Counter.builder("ocs.otp.checked")
                .description("Login codes checked")
                .tag("result", result)
                .register(registry).increment();
    }

    public void pushCommands(String status, Supplier<Number> count) {
        Gauge.builder("ocs.push.commands", count)
                .description("Commands for the gateways in this status")
                .tag("status", status)
                .register(registry);
    }
}
