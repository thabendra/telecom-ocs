package com.thabendra.telecom.charging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * telecom.charging.* in application.yml (group 8).
 *
 * @param sessionExpiry  a session with no update for this long is expired (CH-8): the grant validity
 *                       (1 h) plus a few minutes for a slow last report. Also how long the session
 *                       cache (RD-4) keeps an entry
 * @param expireSessions whether this backend copy runs the expiry every minute; several copies may,
 *                       the PL/SQL skips sessions another copy is working on
 */
@ConfigurationProperties("telecom.charging")
public record ChargingProperties(Duration sessionExpiry, boolean expireSessions) {
}
