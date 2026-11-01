package com.thabendra.telecom.monitoring;

import com.thabendra.telecom.IntegrationTest;
import com.thabendra.telecom.TestCerts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** K8-1: a pod that lost Oracle stops getting traffic, and isn't restarted. Skipped when Docker isn't running. */
@IntegrationTest
class HealthProbesTest {

    @LocalManagementPort
    private int managementPort;

    @Test
    void livenessLooksOnlyAtTheJvm() {   // a restart can't fix a database
        ResponseEntity<String> liveness = get("/actuator/health/liveness");

        assertThat(liveness.getStatusCode().value()).isEqualTo(200);
        assertThat(liveness.getBody()).contains("\"UP\"").doesNotContain("\"db\"", "\"redis\"");
    }

    @Test
    void readinessAlsoLooksAtOracleButNotAtRedis() {   // Redis down has fallbacks: keep the traffic
        ResponseEntity<String> readiness = get("/actuator/health/readiness");

        assertThat(readiness.getStatusCode().value()).isEqualTo(200);
        assertThat(readiness.getBody()).contains("\"db\"").doesNotContain("\"redis\"");
    }

    private ResponseEntity<String> get(String path) {
        return TestCerts.exchange(TestCerts.http(managementPort).get().uri(path));
    }
}
