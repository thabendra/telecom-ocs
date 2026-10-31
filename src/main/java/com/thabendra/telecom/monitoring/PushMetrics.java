package com.thabendra.telecom.monitoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ocs_push_commands{status}: how many gateway commands are waiting (PENDING), delivered but not acked
 * (SENT), or gave up (FAILED). Counted in Oracle each time Prometheus reads the numbers (every 15 s),
 * so every backend copy reports the same totals. A growing SENT or FAILED count means a gateway
 * stopped acking.
 */
@Component
public class PushMetrics {

    public PushMetrics(OcsMetrics metrics, JdbcTemplate jdbc) {
        for (String status : new String[] {"PENDING", "SENT", "FAILED"}) {
            metrics.pushCommands(status, () -> count(jdbc, status));
        }
    }

    // a metrics read must never fail because Oracle is down: then there is no number (NaN)
    private static Number count(JdbcTemplate jdbc, String status) {
        try {
            return jdbc.queryForObject("SELECT COUNT(*) FROM gateway_command WHERE status = ?", Long.class, status);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
