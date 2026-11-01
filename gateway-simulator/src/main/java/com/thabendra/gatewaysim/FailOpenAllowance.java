package com.thabendra.gatewaysim;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * What each user has used while the backend couldn't answer (RL-6), against the limits of
 * {@link FailOpenPolicy}. The limits are per user and start at the user's first fail-open moment; they
 * start again once that usage has been reported to the backend. In memory: a real gateway would keep this
 * on disk, so a restart doesn't lose usage that still has to be charged.
 */
public class FailOpenAllowance {

    private static final class Usage {
        private final Instant since;
        private long mb;

        private Usage(Instant since) {
            this.since = since;
        }
    }

    private final FailOpenPolicy policy;
    private final Clock clock;
    private final Map<String, Usage> users = new HashMap<>();

    public FailOpenAllowance(FailOpenPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    /** Counts this data if it fits in both limits; false means the user's traffic must be blocked. */
    public synchronized boolean use(String msisdn, long mb) {
        Usage usage = users.computeIfAbsent(msisdn, user -> new Usage(clock.instant()));
        if (timeIsUp(usage) || usage.mb + mb > policy.maxMb()) {
            return false;
        }
        usage.mb += mb;
        return true;
    }

    public synchronized boolean timeIsUp(String msisdn) {
        Usage usage = users.get(msisdn);
        return usage != null && timeIsUp(usage);
    }

    /** Data used under fail open that the backend doesn't know about yet. */
    public synchronized long owedMb(String msisdn) {
        Usage usage = users.get(msisdn);
        return usage == null ? 0 : usage.mb;
    }

    /** The usage has been dealt with: the limits start again from nothing. */
    public synchronized void settled(String msisdn) {
        users.remove(msisdn);
    }

    private boolean timeIsUp(Usage usage) {
        return Duration.between(usage.since, clock.instant()).compareTo(policy.maxDuration()) >= 0;
    }
}
