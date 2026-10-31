package com.thabendra.gatewaysim;

import com.thabendra.gatewaysim.CommandClient.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the gateway does with pushed commands: block or unblock the user's traffic, then ack.
 * <ul>
 *   <li>A command delivered again (the ack got lost) is not applied twice: its id is remembered,
 *       but it is acked again so the backend stops redelivering it.</li>
 *   <li>Polls and acks go through the retry policy (section I); an ack that still fails is simply
 *       not sent, and the backend delivers the command again later (PU-4).</li>
 * </ul>
 */
public class CommandPoller {

    /** What one poll did. */
    public record Result(int received, int applied, int acked) {
    }

    private static final Logger log = LoggerFactory.getLogger(CommandPoller.class);

    private final CommandClient client;
    private final RetryingCaller caller;
    private final Set<String> applied = ConcurrentHashMap.newKeySet();             // command ids
    private final Set<String> blocked = ConcurrentHashMap.newKeySet();             // msisdns
    private final Map<String, Integer> allowedKbps = new ConcurrentHashMap<>();   // msisdn -> speed after UNBLOCK

    public CommandPoller(CommandClient client, RetryingCaller caller) {
        this.client = client;
        this.caller = caller;
    }

    public Result pollOnce() {
        List<Command> commands;
        try {
            commands = caller.call(client::poll);
        } catch (RuntimeException pollFailed) {
            // nothing is lost: the commands stay in the backend's outbox until a poll gets them (PU-4)
            log.warn("POLL failed ({}); the commands wait in the backend for the next poll", pollFailed.getMessage());
            return new Result(0, 0, 0);
        }
        int newlyApplied = 0;
        int acked = 0;
        for (Command command : commands) {
            if (applied.add(command.id())) {
                apply(command);
                newlyApplied++;
            } else {
                log.info("COMMAND {} delivered again (attempt {}), already applied", command.id(), command.attempt());
            }
            try {
                caller.call(() -> {
                    client.ack(command.id());
                    return null;
                });
                acked++;
            } catch (RuntimeException ackFailed) {
                log.warn("COMMAND {} applied but not acked ({}); the backend will send it again",
                        command.id(), ackFailed.getMessage());
            }
        }
        return new Result(commands.size(), newlyApplied, acked);
    }

    /** True while a BLOCK is the last thing this gateway applied for the user. */
    public boolean isBlocked(String msisdn) {
        return blocked.contains(msisdn);
    }

    /** The speed the last UNBLOCK allowed, or null. */
    public Integer allowedKbps(String msisdn) {
        return allowedKbps.get(msisdn);
    }

    private void apply(Command command) {
        if ("BLOCK".equals(command.action())) {
            blocked.add(command.msisdn());
            allowedKbps.remove(command.msisdn());
            log.info("COMMAND BLOCK   {} ({}): traffic stopped", command.msisdn(), command.reason());
        } else {
            blocked.remove(command.msisdn());
            allowedKbps.put(command.msisdn(), command.bandwidthKbps() == null ? 0 : command.bandwidthKbps());
            log.info("COMMAND UNBLOCK {} ({}): traffic allowed at {} kbps", command.msisdn(), command.reason(),
                    command.bandwidthKbps());
        }
    }
}
