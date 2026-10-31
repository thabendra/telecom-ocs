package com.thabendra.telecom.push;

import com.thabendra.telecom.gateway.GatewayProperties;
import com.thabendra.telecom.push.GatewayCommand.Action;
import com.thabendra.telecom.push.GatewayCommand.Reason;
import com.thabendra.telecom.subscriber.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

/**
 * The push channel's outbox (PU-1..PU-4), in the gateway_command table.
 * <ul>
 *   <li>Writing a command joins the caller's transaction (a DENY, a suspension, a top-up), so the
 *       command exists exactly when the change it is about was committed: the transactional outbox.</li>
 *   <li>Every allowed gateway gets its own copy: the user may show up on any of them.</li>
 *   <li>A gateway polls for its due commands (PU-3) and acks each one. Without an ack a command is
 *       delivered again after a growing timeout, at most maxAttempts times, then it is FAILED (PU-4).</li>
 * </ul>
 */
@Service
public class GatewayCommands {

    private static final Logger log = LoggerFactory.getLogger(GatewayCommands.class);

    private static final RowMapper<GatewayCommand> COMMAND = (rs, row) -> new GatewayCommand(
            rs.getString("id"), rs.getString("msisdn"), Action.valueOf(rs.getString("action")),
            Reason.valueOf(rs.getString("reason")), (Integer) rs.getObject("bandwidth_kbps", Integer.class),
            rs.getInt("attempts") + 1);

    private final JdbcTemplate jdbc;
    private final GatewayProperties gateways;
    private final PushProperties properties;

    public GatewayCommands(JdbcTemplate jdbc, GatewayProperties gateways, PushProperties properties) {
        this.jdbc = jdbc;
        this.gateways = gateways;
        this.properties = properties;
    }

    /** PU-1: block the user on every gateway (suspended, fraud). */
    @Transactional
    public void block(String msisdn, Reason reason) {
        queue(msisdn, Action.BLOCK, reason, null);
    }

    /** PU-1: a charging DENY, nothing left anywhere; queued once, not on every DENY of the same user. */
    @Transactional
    public void blockOutOfQuota(String msisdn) {
        if (latestAction(msisdn) != Action.BLOCK) {
            queue(msisdn, Action.BLOCK, Reason.OUT_OF_QUOTA, null);
        }
    }

    /** The same, for a DENY on an update, where only the session is known. */
    @Transactional
    public void blockOutOfQuotaForSession(String sessionId) {
        List<String> msisdn = jdbc.queryForList("""
                SELECT s.msisdn FROM charging_session c JOIN subscriber s ON s.id = c.subscriber_id
                 WHERE c.id = ?""", String.class, sessionId);
        if (!msisdn.isEmpty()) {
            blockOutOfQuota(msisdn.get(0));
        }
    }

    /** PU-2: let the user through again at this speed (suspension lifted). */
    @Transactional
    public void unblock(String msisdn, Reason reason, Integer bandwidthKbps) {
        queue(msisdn, Action.UNBLOCK, reason, bandwidthKbps);
    }

    /**
     * PU-2 after a top-up or package purchase: only when the user was blocked for running out of quota.
     * A suspended or fraud-blocked user stays blocked however much they top up.
     */
    @Transactional
    public void unblockIfOutOfQuota(String msisdn, Reason reason, Integer bandwidthKbps) {
        List<Reason> latest = jdbc.query("""
                SELECT reason FROM gateway_command WHERE msisdn = ? AND action = 'BLOCK'
                   AND created_at >= (SELECT NVL(MAX(created_at), TIMESTAMP '1970-01-01 00:00:00')
                                        FROM gateway_command WHERE msisdn = ? AND action = 'UNBLOCK')
                 ORDER BY created_at DESC FETCH FIRST 1 ROWS ONLY""",
                (rs, row) -> Reason.valueOf(rs.getString("reason")), msisdn, msisdn);
        if (!latest.isEmpty() && latest.get(0) == Reason.OUT_OF_QUOTA) {
            queue(msisdn, Action.UNBLOCK, reason, bandwidthKbps);
        }
    }

    /**
     * PU-3 / PU-4: the gateway's due commands, oldest first. Each one is marked SENT with its next
     * redelivery time; SKIP LOCKED lets two pollers of the same gateway take different commands.
     */
    @Transactional
    public List<GatewayCommand> poll(String gateway) {
        int failed = jdbc.update("""
                UPDATE gateway_command SET status = 'FAILED'
                 WHERE gateway = ? AND status = 'SENT' AND attempts >= ? AND next_attempt_at <= SYSTIMESTAMP""",
                gateway, properties.maxAttempts());
        if (failed > 0) {
            log.warn("{} command(s) for {} not acked after {} deliveries, marked FAILED",
                    failed, gateway, properties.maxAttempts());
        }

        List<GatewayCommand> due = jdbc.query(connection -> {
            PreparedStatement select = connection.prepareStatement("""
                    SELECT id, msisdn, action, reason, bandwidth_kbps, attempts FROM gateway_command
                     WHERE gateway = ? AND status IN ('PENDING', 'SENT') AND next_attempt_at <= SYSTIMESTAMP
                     ORDER BY created_at
                       FOR UPDATE SKIP LOCKED""");
            select.setString(1, gateway);
            select.setMaxRows(properties.batchSize());
            return select;
        }, COMMAND);

        for (GatewayCommand command : due) {
            jdbc.update("""
                    UPDATE gateway_command
                       SET status = 'SENT', attempts = attempts + 1,
                           next_attempt_at = SYSTIMESTAMP + NUMTODSINTERVAL(?, 'SECOND')
                     WHERE id = ?""",
                    properties.redeliveryAfter(command.attempt()).toMillis() / 1000.0, command.id());
        }
        return due;
    }

    /** The gateway applied it. Acking twice is fine; another gateway's command is "not found". */
    @Transactional
    public void ack(String gateway, String commandId) {
        int updated = jdbc.update("""
                UPDATE gateway_command SET status = 'ACKED', acked_at = SYSTIMESTAMP
                 WHERE id = ? AND gateway = ? AND status <> 'ACKED'""", commandId, gateway);
        if (updated == 0) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM gateway_command WHERE id = ? AND gateway = ?", Integer.class,
                    commandId, gateway);
            if (exists == null || exists == 0) {
                throw new NotFoundException("Command " + commandId + " not found");
            }
        }
    }

    private void queue(String msisdn, Action action, Reason reason, Integer bandwidthKbps) {
        for (String gateway : gateways.allowed()) {
            jdbc.update("""
                    INSERT INTO gateway_command (id, gateway, msisdn, action, reason, bandwidth_kbps)
                    VALUES (?, ?, ?, ?, ?, ?)""",
                    UUID.randomUUID().toString(), gateway, msisdn, action.name(), reason.name(), bandwidthKbps);
        }
    }

    private Action latestAction(String msisdn) {
        List<Action> latest = jdbc.query("""
                SELECT action FROM gateway_command WHERE msisdn = ?
                 ORDER BY created_at DESC FETCH FIRST 1 ROWS ONLY""",
                (rs, row) -> Action.valueOf(rs.getString("action")), msisdn);
        return latest.isEmpty() ? null : latest.get(0);
    }
}
