package com.thabendra.gatewaysim;

import com.thabendra.gatewaysim.ChargingClient.Answer;
import com.thabendra.gatewaysim.ChargingClient.Grant;
import com.thabendra.gatewaysim.ChargingClient.Release;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * One charging session as a gateway runs it (TE-1):
 * <pre>
 *   start (request 0) ─► ALLOW n MB ─► user uses it ─► update (request 1, used) ─► ... ─► release (used)
 *                    └─► DENY ─► block the user's traffic
 * </pre>
 * Each request gets a new Idempotency-Key. With replayEachRequest the same request is sent twice
 * with the same key, as a retry after a timeout would be; the backend must answer the same and
 * charge once.
 */
public class GatewaySimulator {

    /** How the session ended. */
    public record Outcome(String sessionId, boolean blocked, int updates, long totalUsedMb) {
    }

    private static final Logger log = LoggerFactory.getLogger(GatewaySimulator.class);

    private final ChargingClient client;
    private final SimulatorProperties props;

    public GatewaySimulator(ChargingClient client, SimulatorProperties props) {
        this.client = client;
        this.props = props;
    }

    public Outcome run() {
        log.info("START  {} {} asks {} MB", props.msisdn(), props.app(), props.chunkMb());
        String startKey = ChargingClient.newKey();
        Grant grant = send(() -> client.start(startKey, props.msisdn(), props.app(), props.chunkMb()));
        String sessionId = grant.sessionId();
        logGrant(grant);
        if (!grant.allowed()) {
            log.info("BLOCK  traffic of {} (session {} closed by backend)", props.msisdn(), sessionId);
            return new Outcome(sessionId, true, 0, 0);
        }

        long requestNumber = 0;
        boolean blocked = false;
        for (int i = 0; i < props.maxUpdates(); i++) {
            long used = grant.grantedMb() * props.usedPercent() / 100;
            long number = ++requestNumber;
            String key = ChargingClient.newKey();
            log.info("UPDATE #{} used {} MB, asks {} MB", number, used, props.chunkMb());
            grant = send(() -> client.update(key, sessionId, number, used, props.chunkMb()));
            logGrant(grant);
            if (!grant.allowed()) {
                log.info("BLOCK  traffic of {}: nothing left", props.msisdn());
                blocked = true;
                break;
            }
        }

        // after a DENY nothing is held any more, so the release reports 0
        long finalUsed = blocked ? 0 : Math.min(props.finalUsedMb(), grant.grantedMb());
        long number = ++requestNumber;
        String key = ChargingClient.newKey();
        log.info("RELEASE #{} used {} MB", number, finalUsed);
        Release release = send(() -> client.release(key, sessionId, number, finalUsed));
        log.info("CLOSED session {}: {} MB charged in total", sessionId, release.totalUsedMb());
        return new Outcome(sessionId, blocked, (int) requestNumber - 1, release.totalUsedMb());
    }

    // sends the request; in replay mode sends it a second time with the same key and checks the answer matches
    private <T> T send(Supplier<Answer<T>> request) {
        Answer<T> first = request.get();
        if (props.replayEachRequest()) {
            Answer<T> again = request.get();
            if (!again.replayed() || !again.body().equals(first.body())) {
                throw new IllegalStateException("Retry with the same Idempotency-Key was not replayed: " + again);
            }
            log.info("       retry with same key -> replayed, same answer");
        }
        return first.body();
    }

    private static void logGrant(Grant grant) {
        if (grant.allowed()) {
            log.info("       ALLOW {} MB at {} kbps from {}{} (valid {} s)", grant.grantedMb(), grant.bandwidthKbps(),
                    grant.source(), grant.packageCode() == null ? "" : " " + grant.packageCode(),
                    grant.validitySeconds());
        } else {
            log.info("       DENY  action {}", grant.action());
        }
    }
}
