package com.thabendra.telecom.charging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * telecom.charging.* in application.yml (groups 8 and 9).
 *
 * @param sessionExpiry  a session with no update for this long is expired (CH-8): the grant validity
 *                       (1 h) plus a few minutes for a slow last report. Also how long the session
 *                       cache (RD-4) keeps an entry
 * @param expireSessions whether this backend copy runs the expiry every minute; several copies may,
 *                       the PL/SQL skips sessions another copy is working on
 * @param grant          grant sizes when the gateway leaves the size to us (CH-5, RL-3)
 */
@ConfigurationProperties("telecom.charging")
public record ChargingProperties(Duration sessionExpiry, boolean expireSessions, Grant grant) {

    /**
     * @param defaultMb        a normal grant
     * @param heavyMb          a heavy user's grant: fewer update requests for the same traffic
     * @param heavyThresholdMb MB used within heavyWindow that make a subscriber a heavy user
     * @param heavyWindow      the sliding window (RL-3)
     */
    public record Grant(long defaultMb, long heavyMb, long heavyThresholdMb, Duration heavyWindow) {
    }
}
