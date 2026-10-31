package com.thabendra.telecom.charging;

import com.thabendra.telecom.audit.AuditContext;
import com.thabendra.telecom.monitoring.OcsMetrics;
import com.thabendra.telecom.push.GatewayCommands;
import com.thabendra.telecom.subscriber.NotFoundException;
import com.thabendra.telecom.subscriber.OracleErrors;
import com.thabendra.telecom.usage.UsageService.App;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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

    /**
     * ALLOW: a new grant. DENY: nothing left, block. REPORTED: the gateway asked for 0 MB (it only
     * reported usage); the usage is charged, nothing new is granted, and nobody is blocked.
     */
    public enum Result { ALLOW, DENY, REPORTED }

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
    private final SessionCache cache;
    private final GrantPolicy policy;
    private final UsageWindow usage;
    private final JdbcTemplate jdbc;

    public ChargingService(ChargingSessionRepository sessions, GatewayCommands commands, AuditContext audit,
                           OcsMetrics metrics, ObservationRegistry observations, SessionCache cache,
                           GrantPolicy policy, UsageWindow usage, JdbcTemplate jdbc) {
        this.sessions = sessions;
        this.commands = commands;
        this.audit = audit;
        this.metrics = metrics;
        this.observations = observations;
        this.cache = cache;
        this.policy = policy;
        this.usage = usage;
        this.jdbc = jdbc;
    }

    // a DENY also queues BLOCK for every gateway (PU-1), in this same transaction.
    // requestedMb null: the gateway leaves the grant size to us (CH-5)
    @Transactional
    public Grant startSession(String msisdn, App app, Long requestedMb) {
        String sessionId = UUID.randomUUID().toString();
        long size = policy.size(msisdn, requestedMb, 0);
        // the PL/SQL writes GRANT / DENY into the audit trail with the calling gateway's name (OB-1)
        Grant grant = toGrant(sessionId, size, inOracle("start", () -> audit.asCaller(
                () -> call(() -> sessions.startSession(sessionId, msisdn, app.name(), size)))));
        metrics.chargingDecision("start", grant.result() == Result.ALLOW, AuditContext.callerName());
        if (grant.result() == Result.DENY) {
            commands.blockOutOfQuota(msisdn);
        } else {
            cache.remember(sessionId, AuditContext.callerName(), msisdn);   // RD-4
        }
        return grant;
    }

    // CH-7: only the gateway that started the session may update it (checked in the cache, then in Oracle)
    @Transactional
    public Grant updateSession(String sessionId, long requestNumber, long usedMb, Long requestedMb) {
        SessionCache.Owner owner = checkOwner(sessionId);
        String msisdn = owner != null ? owner.msisdn() : msisdnOf(sessionId);
        // the usage in this report counts for the heavy-user check already (RL-3)
        long size = msisdn != null ? policy.size(msisdn, requestedMb, usedMb)
                : (requestedMb != null ? requestedMb : 0);   // unknown session: Oracle answers 404
        Grant grant = toGrant(sessionId, size, inOracle("update", () -> audit.asCaller(
                () -> call(() -> sessions.updateSession(sessionId, requestNumber, usedMb, size)))));
        if (msisdn != null) {
            usage.add(msisdn, usedMb);
        }
        if (size > 0) {   // asking for 0 MB (only reporting usage) is no decision, as in the audit
            metrics.chargingDecision("update", grant.result() == Result.ALLOW, AuditContext.callerName());
        }
        if (grant.result() == Result.DENY) {
            commands.blockOutOfQuotaForSession(sessionId);
        } else if (owner != null) {
            cache.remember(sessionId, owner.gateway(), owner.msisdn());   // alive again: keep it as long
        }
        return grant;
    }

    @Transactional
    public Release releaseSession(String sessionId, long requestNumber, long usedMb) {
        SessionCache.Owner owner = checkOwner(sessionId);
        Long totalUsedMb = inOracle("release", () -> audit.asCaller(
                () -> call(() -> sessions.releaseSession(sessionId, requestNumber, usedMb))));
        String msisdn = owner != null ? owner.msisdn() : msisdnOf(sessionId);
        if (msisdn != null) {
            usage.add(msisdn, usedMb);
        }
        cache.forget(sessionId);
        return new Release(sessionId, totalUsedMb);
    }

    // RD-4: a cached owner that isn't the caller is refused at once, with the same answer as an unknown
    // session; no cache entry means Oracle decides (lock_open_session checks the owner too)
    private SessionCache.Owner checkOwner(String sessionId) {
        SessionCache.Owner owner = cache.find(sessionId).orElse(null);
        if (owner != null && !owner.gateway().equals(AuditContext.callerName())) {
            throw new NotFoundException("Charging session " + sessionId + " not found");
        }
        return owner;
    }

    // the session's subscriber when the cache doesn't know it; null for a session that doesn't exist
    private String msisdnOf(String sessionId) {
        return jdbc.query("""
                        SELECT s.msisdn FROM charging_session c JOIN subscriber s ON s.id = c.subscriber_id
                         WHERE c.id = ?""", (rs, i) -> rs.getString(1), sessionId)
                .stream().findFirst().orElse(null);
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

    private static Grant toGrant(String sessionId, long askedMb, Map<String, Object> out) {
        long granted = number(out, "P_GRANTED_MB").longValue();
        if (granted == 0 && askedMb == 0) {
            return new Grant(sessionId, Result.REPORTED, 0, null, null, null, null, null);
        }
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
