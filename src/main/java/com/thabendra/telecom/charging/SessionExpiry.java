package com.thabendra.telecom.charging;

import com.thabendra.telecom.monitoring.OcsMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.util.Map;

/**
 * CH-8: closes sessions a gateway forgot (it crashed, lost the user, never sent the release). Their
 * reservation would otherwise hold the user's quota forever. CHARGING.EXPIRE_STALE_SESSIONS does the
 * work in Oracle; this only calls it. Nothing is charged for an expired grant: no usage was reported,
 * and charging a guess would be worse than charging nothing.
 * <p>
 * Every backend copy may run it: the PL/SQL locks each subscriber with SKIP LOCKED, so two runs at
 * the same moment take different sessions. (In production an Oracle DBMS_SCHEDULER job could run the
 * same procedure instead; it needs the CREATE JOB privilege.)
 */
@Service
public class SessionExpiry {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiry.class);

    private final SimpleJdbcCall expireCall;
    private final ChargingProperties properties;
    private final OcsMetrics metrics;

    public SessionExpiry(JdbcTemplate jdbc, ChargingProperties properties, OcsMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
        this.expireCall = new SimpleJdbcCall(jdbc)
                .withCatalogName("CHARGING")
                .withProcedureName("EXPIRE_STALE_SESSIONS")
                .withoutProcedureColumnMetaDataAccess()
                .declareParameters(
                        new SqlParameter("P_IDLE_SECONDS", Types.NUMERIC),
                        new SqlOutParameter("P_EXPIRED", Types.NUMERIC));
    }

    /** Expires what is stale now; returns how many sessions it closed. */
    @Transactional
    public int expireStale() {
        Map<String, Object> out = expireCall.execute(
                Map.of("P_IDLE_SECONDS", properties.sessionExpiry().toSeconds()));
        int expired = ((Number) out.get("P_EXPIRED")).intValue();
        if (expired > 0) {
            metrics.sessionsExpired(expired);
            log.info("expired {} charging session(s) with no update for {}", expired, properties.sessionExpiry());
        }
        return expired;
    }

    /** Runs the expiry every minute, unless telecom.charging.expire-sessions is false (the tests call it). */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "telecom.charging.expire-sessions", havingValue = "true", matchIfMissing = true)
    static class Schedule {

        private final SessionExpiry expiry;

        Schedule(SessionExpiry expiry) {
            this.expiry = expiry;
        }

        @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
        void run() {
            try {
                expiry.expireStale();
            } catch (RuntimeException e) {   // Oracle down: try again next minute
                log.warn("session expiry failed: {}", e.getMessage());
            }
        }
    }
}
