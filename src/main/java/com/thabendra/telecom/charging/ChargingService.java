package com.thabendra.telecom.charging;

import com.thabendra.telecom.push.GatewayCommands;
import com.thabendra.telecom.subscriber.OracleErrors;
import com.thabendra.telecom.usage.UsageService.App;
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

    public ChargingService(ChargingSessionRepository sessions, GatewayCommands commands) {
        this.sessions = sessions;
        this.commands = commands;
    }

    // a DENY also queues BLOCK for every gateway (PU-1), in this same transaction
    @Transactional
    public Grant startSession(String msisdn, App app, long requestedMb) {
        String sessionId = UUID.randomUUID().toString();
        Grant grant = toGrant(sessionId, call(() -> sessions.startSession(sessionId, msisdn, app.name(), requestedMb)));
        if (grant.result() == Result.DENY) {
            commands.blockOutOfQuota(msisdn);
        }
        return grant;
    }

    @Transactional
    public Grant updateSession(String sessionId, long requestNumber, long usedMb, long requestedMb) {
        Grant grant = toGrant(sessionId,
                call(() -> sessions.updateSession(sessionId, requestNumber, usedMb, requestedMb)));
        if (grant.result() == Result.DENY) {
            commands.blockOutOfQuotaForSession(sessionId);
        }
        return grant;
    }

    @Transactional
    public Release releaseSession(String sessionId, long requestNumber, long usedMb) {
        Long totalUsedMb = call(() -> sessions.releaseSession(sessionId, requestNumber, usedMb));
        return new Release(sessionId, totalUsedMb);
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
