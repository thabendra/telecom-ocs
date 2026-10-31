package com.thabendra.telecom.charging;

import com.thabendra.telecom.audit.AuditContext;
import com.thabendra.telecom.monitoring.OcsMetrics;
import com.thabendra.telecom.push.GatewayCommands;
import com.thabendra.telecom.subscriber.OracleErrors;
import com.thabendra.telecom.usage.UsageService.App;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Online charging for the network gateway: start a session, update it as quota is used, release it.
 * Owns the transaction and the answer the gateway gets (ALLOW / DENY + BLOCK, grant validity);
 * the reserve -> commit work itself runs in Oracle through {@link ChargingSessionRepository}.
 */
@Service
public class ChargingService {

    public enum Result { ALLOW, DENY }

    /** What the gateway must do when the result is DENY. */
    public enum Action { BLOCK }

    /** How long the gateway may use a grant before it has to report back. */
    static final int GRANT_VALIDITY_SECONDS = 3600;

    /**
     * Answer to start / update. ALLOW: the gateway may pass grantedMb more at bandwidthKbps.
     * DENY: nothing left anywhere, the gateway must apply action.
     */
    public record Grant(String sessionId, Result result, long grantedMb, Integer bandwidthKbps, String source,
                        String packageCode, Integer validitySeconds, Action action) {
    }

    public record Release(String sessionId, long totalUsedMb) {
    }

    private final ChargingSessionRepository sessions;
    private final GatewayCommands commands;
    private final AuditContext audit;
    private final OcsMetrics metrics;
    private final ObservationRegistry observations;

    public ChargingService(ChargingSessionRepository sessions, GatewayCommands commands, AuditContext audit,
                           OcsMetrics metrics, ObservationRegistry observations) {
        this.sessions = sessions;
        this.commands = commands;
        this.audit = audit;
        this.metrics = metrics;
        this.observations = observations;
    }

    // a DENY also queues BLOCK for every gateway (PU-1), in this same transaction
    @Transactional
    public Grant startSession(String msisdn, App app, long requestedMb) {
        String sessionId = UUID.randomUUID().toString();
        // the PL/SQL writes GRANT / DENY into the audit trail with the calling gateway's name (OB-1)
        Grant grant = toGrant(sessionId, inOracle("start", () -> audit.asCaller(
                () -> call(() -> sessions.startSession(sessionId, msisdn, app.name(), requestedMb)))));
        metrics.chargingDecision("start", grant.result() == Result.ALLOW, AuditContext.callerName());
        if (grant.result() == Result.DENY) {
            commands.blockOutOfQuota(msisdn);
        }
        return grant;
    }

    @Transactional
    public Grant updateSession(String sessionId, long requestNumber, long usedMb, long requestedMb) {
        Grant grant = toGrant(sessionId, inOracle("update", () -> audit.asCaller(
                () -> call(() -> sessions.updateSession(sessionId, requestNumber, usedMb, requestedMb)))));
        if (requestedMb > 0) {   // asking for 0 MB (the last report) is no decision, as in the audit
            metrics.chargingDecision("update", grant.result() == Result.ALLOW, AuditContext.callerName());
        }
        if (grant.result() == Result.DENY) {
            commands.blockOutOfQuotaForSession(sessionId);
        }
        return grant;
    }

    @Transactional
    public Release releaseSession(String sessionId, long requestNumber, long usedMb) {
        Long totalUsedMb = inOracle("release",
                () -> call(() -> sessions.releaseSession(sessionId, requestNumber, usedMb)));
        return new Release(sessionId, totalUsedMb);
    }

    // OB-3: the CHARGING procedure as its own span in the trace, so the time spent in Oracle shows apart
    private <T> T inOracle(String operation, Supplier<T> procedure) {
        return Observation.createNotStarted("ocs.charging.oracle", observations)
                .contextualName("CHARGING." + operation + "_session")
                .lowCardinalityKeyValue("operation", operation)
                .observe(procedure);
    }

    // the repository proxy turns JPA errors into DataAccessException; map the ORA-200xx codes to domain errors
    private static <T> T call(Supplier<T> procedure) {
        try {
            return procedure.get();
        } catch (DataAccessException e) {
            throw OracleErrors.translate(e);
        }
    }

    private static Grant toGrant(String sessionId, Map<String, Object> out) {
        long granted = number(out, "P_GRANTED_MB").longValue();
        if (granted == 0) {
            return new Grant(sessionId, Result.DENY, 0, null, null, null, null, Action.BLOCK);
        }
        return new Grant(sessionId, Result.ALLOW, granted, number(out, "P_BANDWIDTH_KBPS").intValue(),
                (String) out.get("P_SOURCE"), (String) out.get("P_PACKAGE_CODE"), GRANT_VALIDITY_SECONDS, null);
    }

    private static Number number(Map<String, Object> out, String name) {
        return (Number) out.get(name);
    }
}
