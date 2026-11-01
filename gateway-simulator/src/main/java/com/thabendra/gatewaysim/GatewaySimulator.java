package com.thabendra.gatewaysim;

import com.thabendra.gatewaysim.ChargingClient.Answer;
import com.thabendra.gatewaysim.ChargingClient.Grant;
import com.thabendra.gatewaysim.ChargingClient.Release;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
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
 * If the backend can't be reached in time (retries, time budget or circuit breaker, section I), the
 * gateway fails open under the failure contract (RL-6):
 * <pre>
 *   no answer ─► traffic through, counted ─► try the same request again every checkEvery
 *                  │ 50 MB or 5 minutes             │ answered
 *                  ▼                                ▼
 *               BLOCK until it answers           session goes on; the counted MB are charged at its end
 * </pre>
 * The same request means the same key and number, so it can't charge twice if the lost one did arrive.
 * The counted MB get a short session of their own (start + release), because Oracle charges at most what
 * is reserved, so adding them to the next report would lose them. A real gateway keeps blocking until the
 * backend answers; this one-shot run ends at the time limit and says what is still owed.
 */
public class GatewaySimulator {

    /** How the session ended. unchargedMb: used under fail open, and not charged by the end of this run. */
    public record Outcome(String sessionId, boolean blocked, int updates, long totalUsedMb, boolean failedOpen,
                          long unchargedMb) {
    }

    /** The backend stayed away for the whole time limit. */
    private static final class OutageTooLongException extends RuntimeException {
    }

    private static final Logger log = LoggerFactory.getLogger(GatewaySimulator.class);

    private final ChargingClient client;
    private final SimulatorProperties props;
    private final ObservationRegistry observations;
    private final RetryingCaller.Sleeper sleeper;
    private final FailOpenAllowance allowance;
    private final Supplier<String> newKey;
    private boolean failedOpen;

    public GatewaySimulator(ChargingClient client, SimulatorProperties props) {
        this(client, props, ObservationRegistry.NOOP);
    }

    public GatewaySimulator(ChargingClient client, SimulatorProperties props, ObservationRegistry observations) {
        this(client, props, observations, duration -> Thread.sleep(duration), Clock.systemUTC(), ChargingClient::newKey);
    }

    GatewaySimulator(ChargingClient client, SimulatorProperties props, ObservationRegistry observations,
                     RetryingCaller.Sleeper sleeper, Clock clock, Supplier<String> newKey) {
        this.client = client;
        this.props = props;
        this.observations = observations;
        this.sleeper = sleeper;
        this.allowance = new FailOpenAllowance(props.failOpen(), clock);
        this.newKey = newKey;
    }

    // OB-3: the whole session is one trace; every call inside it (retries too) is a child span, and the
    // backend continues the same trace from the traceparent header
    public Outcome run() {
        return Observation.createNotStarted("gateway.session", observations)
                .contextualName("charging session " + props.app())
                .lowCardinalityKeyValue("protocol", String.valueOf(props.protocol()))
                .lowCardinalityKeyValue("app", String.valueOf(props.app()))
                .observe(() -> {
                    String traceId = MDC.get("traceId");
                    if (traceId != null) {
                        log.info("TRACE  {} (Jaeger: http://localhost:16686/trace/{})", traceId, traceId);
                    }
                    try {
                        return chargeWhatIsOwed(runSession());
                    } catch (OutageTooLongException e) {
                        long owed = allowance.owedMb(props.msisdn());
                        log.warn("NOT CHARGED {} MB of {}: no answer for {}; traffic stays blocked until the backend"
                                + " answers", owed, props.msisdn(), props.failOpen().maxDuration());
                        return new Outcome(null, true, 0, 0, true, owed);
                    }
                });
    }

    private Outcome runSession() {
        log.info("START  {} {} asks {} MB (over {})", props.msisdn(), props.app(), props.chunkMb(), props.protocol());
        String startKey = newKey.get();
        Grant grant = send(() -> client.start(startKey, props.msisdn(), props.app(), props.chunkMb()));
        String sessionId = grant.sessionId();
        logGrant(grant);
        if (!grant.allowed()) {
            log.info("BLOCK  traffic of {} (session {} closed by backend)", props.msisdn(), sessionId);
            return new Outcome(sessionId, true, 0, 0, failedOpen, 0);
        }

        long requestNumber = 0;
        boolean blocked = false;
        for (int i = 0; i < props.maxUpdates(); i++) {
            long used = grant.grantedMb() * props.usedPercent() / 100;
            long number = ++requestNumber;
            String key = newKey.get();
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
        String key = newKey.get();
        log.info("RELEASE #{} used {} MB", number, finalUsed);
        Release release = send(() -> client.release(key, sessionId, number, finalUsed));
        log.info("CLOSED session {}: {} MB charged in total", sessionId, release.totalUsedMb());
        return new Outcome(sessionId, blocked, (int) requestNumber - 1, release.totalUsedMb(), failedOpen, 0);
    }

    // RL-6: the backend is back, so the MB used meanwhile get a session of their own. Whatever it can't
    // charge (no balance) is lost, and that loss is what the contract's limits keep small.
    private Outcome chargeWhatIsOwed(Outcome session) {
        String msisdn = props.msisdn();
        long owed = allowance.owedMb(msisdn);
        if (owed == 0) {
            return session;
        }
        try {
            Grant grant = client.start(newKey.get(), msisdn, props.app(), owed).body();
            long charged = 0;
            if (grant.allowed()) {
                charged = Math.min(owed, grant.grantedMb());
                client.release(newKey.get(), grant.sessionId(), 1, charged);
            }
            allowance.settled(msisdn);
            log.info("CHARGED {} of the {} MB used under fail open", charged, owed);
            return new Outcome(session.sessionId(), session.blocked() || !grant.allowed(), session.updates(),
                    session.totalUsedMb(), true, owed - charged);
        } catch (FailOpenException e) {
            log.warn("NOT CHARGED {} MB of {}: {}", owed, msisdn, e.getMessage());
            return new Outcome(session.sessionId(), session.blocked(), session.updates(), session.totalUsedMb(), true,
                    owed);
        }
    }

    // RL-6: no answer in time: traffic goes through within the limits while the same request is tried again
    private <T> Answer<T> callThroughOutage(Supplier<Answer<T>> request) {
        try {
            return request.get();
        } catch (FailOpenException e) {
            failedOpen = true;
            log.warn("FAIL OPEN: {}; letting {}'s traffic through, {} MB for {} at most, and charging it later",
                    e.getMessage(), props.msisdn(), props.failOpen().maxMb(), props.failOpen().maxDuration());
        }
        boolean blocked = false;
        while (true) {
            if (allowance.use(props.msisdn(), props.failOpen().mbPerCheck())) {
                log.info("       traffic through under fail open: {} MB so far", allowance.owedMb(props.msisdn()));
            } else if (!blocked) {
                blocked = true;
                log.warn("BLOCK  traffic of {}: fail-open limit reached, until the backend answers", props.msisdn());
            }
            waitForNextCheck();
            if (allowance.timeIsUp(props.msisdn())) {
                throw new OutageTooLongException();
            }
            try {
                Answer<T> answer = request.get();
                if (blocked) {
                    log.info("UNBLOCK traffic of {}: the backend answers again", props.msisdn());
                }
                return answer;
            } catch (FailOpenException stillDown) {
                log.debug("still no answer: {}", stillDown.getMessage());
            }
        }
    }

    private void waitForNextCheck() {
        try {
            sleeper.sleep(props.failOpen().checkEvery());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new OutageTooLongException();
        }
    }

    // sends the request; in replay mode sends it a second time with the same key and checks the answer matches
    private <T> T send(Supplier<Answer<T>> request) {
        Answer<T> first = callThroughOutage(request);
        if (props.replayEachRequest()) {
            Answer<T> again = callThroughOutage(request);
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
