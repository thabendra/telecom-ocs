package com.thabendra.telecom.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * telecom.rate-limit.* in application.yml.
 *
 * @param capacity        burst size per gateway (tokens a full bucket holds)
 * @param refillPerSecond steady rate per gateway
 * @param instances       most backend copies that can run (the autoscaler's maximum); when Redis is
 *                        down each copy allows rate / instances, so all copies together stay at or under the rate
 * @param overrides       a different limit for some gateways, by certificate name
 * @param maxInFlight     RL-4: requests one backend copy runs at the same moment; the same number as
 *                        its database pool. Rule: pool size x most copies <= sessions the database can take
 * @param inFlightWait    how long a request may wait for a free slot before it gets 503
 * @param reservedSlots   RL-5: of maxInFlight, the slots only update and release may use
 * @param ongoingWait     RL-5: how long update and release may wait for a free slot, longer than other work
 */
@ConfigurationProperties("telecom.rate-limit")
public record RateLimitProperties(long capacity, double refillPerSecond, int instances, Map<String, Limit> overrides,
                                  int maxInFlight, Duration inFlightWait, int reservedSlots, Duration ongoingWait) {

    /** One gateway's bucket: burst size and steady rate. */
    public record Limit(long capacity, double refillPerSecond) {

        public double refillPerMs() {
            return refillPerSecond / 1000.0;
        }

        /** This copy's share when it has to count on its own (RD-6). */
        public Limit share(int instances) {
            int copies = Math.max(1, instances);
            return new Limit(Math.max(1, capacity / copies), refillPerSecond / copies);
        }
    }

    public Limit limitFor(String gateway) {
        Limit override = overrides == null ? null : overrides.get(gateway);
        return override != null ? override : new Limit(capacity, refillPerSecond);
    }
}
