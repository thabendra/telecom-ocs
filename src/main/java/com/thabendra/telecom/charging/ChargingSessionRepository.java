package com.thabendra.telecom.charging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.query.Procedure;
import org.springframework.data.repository.query.Param;

import java.util.Map;

/**
 * Spring Data repository for charging sessions. CRUD reads come from JpaRepository; the three
 * {@code @Procedure} methods call the CHARGING PL/SQL package through the named stored procedures
 * declared on {@link ChargingSession}. With several OUT parameters, Spring Data returns them as a
 * Map keyed by parameter name (P_GRANTED_MB, P_BANDWIDTH_KBPS, ...).
 * They run in the caller's transaction (see {@link ChargingService}).
 */
public interface ChargingSessionRepository extends JpaRepository<ChargingSession, String> {

    /** CHARGING.START_SESSION: creates the session row and reserves the first chunk. */
    @Procedure(name = "ChargingSession.startSession")
    Map<String, Object> startSession(@Param("P_SESSION_ID") String sessionId,
                                     @Param("P_MSISDN") String msisdn,
                                     @Param("P_APP") String app,
                                     @Param("P_REQUESTED_MB") Long requestedMb);

    /**
     * CHARGING.UPDATE_SESSION: commits the used MB of the active grant and reserves the next chunk.
     * requestNumber is the gateway's counter for this session (1, 2, ...); a repeated number is refused.
     */
    @Procedure(name = "ChargingSession.updateSession")
    Map<String, Object> updateSession(@Param("P_SESSION_ID") String sessionId,
                                      @Param("P_REQUEST_NUMBER") Long requestNumber,
                                      @Param("P_USED_MB") Long usedMb,
                                      @Param("P_REQUESTED_MB") Long requestedMb);

    /**
     * CHARGING.RELEASE_SESSION: commits the used MB, frees the rest and closes the session.
     * Only one OUT parameter (P_TOTAL_USED_MB), so Spring Data returns it directly, not a Map.
     */
    @Procedure(name = "ChargingSession.releaseSession")
    Long releaseSession(@Param("P_SESSION_ID") String sessionId,
                        @Param("P_REQUEST_NUMBER") Long requestNumber,
                        @Param("P_USED_MB") Long usedMb);
}
