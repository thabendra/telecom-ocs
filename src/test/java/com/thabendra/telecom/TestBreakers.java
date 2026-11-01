package com.thabendra.telecom;

import com.thabendra.telecom.monitoring.OcsMetrics;
import com.thabendra.telecom.resilience.CircuitProperties;
import com.thabendra.telecom.resilience.Circuits;
import com.thabendra.telecom.resilience.RedisBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;

/** Circuit breakers for tests that build their objects by hand, with the settings of application.yml. */
public final class TestBreakers {

    private TestBreakers() {
    }

    public static CircuitProperties properties() {
        return new CircuitProperties(
                new CircuitProperties.Redis(20, 10, 50, Duration.ofMillis(100), 50, Duration.ofSeconds(5), 3),
                new CircuitProperties.SigningKeys(3, Duration.ofSeconds(30), Duration.ofSeconds(2),
                        Duration.ofMinutes(10), Duration.ofSeconds(30), false));
    }

    public static RedisBreaker redis() {
        return new RedisBreaker(new Circuits(new OcsMetrics(new SimpleMeterRegistry())), properties());
    }
}
