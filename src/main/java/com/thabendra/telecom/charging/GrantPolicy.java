package com.thabendra.telecom.charging;

import org.springframework.stereotype.Component;

/**
 * CH-5: how many MB to grant at a time.
 * <ul>
 *   <li>the gateway asked for an amount: that amount (as before)</li>
 *   <li>the gateway left it to us (no requestedMb): 100 MB, or 250 MB for a heavy user (RL-3), so a
 *       user streaming video sends half as many updates</li>
 *   <li>little left: the grant is what is left, a smaller "final" grant (the CHARGING package's
 *       reserve takes LEAST(asked, left) from the bucket)</li>
 * </ul>
 * Every update is a round trip and an Oracle transaction, so bigger grants for the users who need
 * them cut load; the cost is that more quota is held per session (given back on release / expiry).
 */
@Component
public class GrantPolicy {

    private final UsageWindow usage;
    private final ChargingProperties.Grant grant;

    public GrantPolicy(UsageWindow usage, ChargingProperties properties) {
        this.usage = usage;
        this.grant = properties.grant();
    }

    /**
     * @param requestedMb what the gateway asked for, or null when it leaves the size to us
     * @param justUsedMb  usage in this same request, not in the window yet (0 for a start)
     */
    public long size(String msisdn, Long requestedMb, long justUsedMb) {
        if (requestedMb != null) {
            return requestedMb;
        }
        return usage.isHeavy(usage.total(msisdn) + justUsedMb) ? grant.heavyMb() : grant.defaultMb();
    }
}
