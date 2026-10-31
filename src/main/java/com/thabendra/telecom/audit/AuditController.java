package com.thabendra.telecom.audit;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * OB-1, staff only: what happened to one number, newest first.
 * <pre>
 *   GET /api/subscribers/{msisdn}/audit?from=2026-10-01T00:00:00Z&to=...&page=0&size=50
 * </pre>
 * Customer care uses it to answer "why was I blocked?": the DENY, the BLOCK per gateway, whether each
 * gateway acked it, and the later UNBLOCK after the top-up. Times are UTC.
 */
@RestController
public class AuditController {

    /** One audit row; a null field is not part of this kind of event. */
    public record AuditEvent(Instant time, String event, String msisdn, String gateway, String actor,
                             String sessionId, String commandId, String reason, Long requestedMb, Long grantedMb,
                             Integer bandwidthKbps, String source, String packageCode) {
    }

    private final JdbcTemplate jdbc;

    public AuditController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // from / to: ISO-8601 instants, e.g. 2026-10-01T00:00:00Z; default the last 30 days
    @GetMapping("/api/subscribers/{msisdn}/audit")
    public List<AuditEvent> events(
            @PathVariable String msisdn,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int size) {
        Instant start = from != null ? from : (to != null ? to : Instant.now()).minusSeconds(30L * 24 * 3600);
        if (to != null && !start.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        // event_time is UTC in a plain TIMESTAMP: compare with UTC wall-clock times
        List<Object> args = new ArrayList<>(List.of(msisdn, utc(start)));
        if (to != null) {
            args.add(utc(to));
        }
        args.add((long) page * size);
        args.add(size);
        String until = to != null ? " AND event_time < ?" : "";
        return jdbc.query("""
                SELECT event_time, event, msisdn, gateway, actor, session_id, command_id, reason,
                       requested_mb, granted_mb, bandwidth_kbps, source, package_code
                  FROM audit_event
                 WHERE msisdn = ?
                   AND event_time >= ?%s
                 ORDER BY event_time DESC, id DESC
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY""".formatted(until),
                (rs, i) -> toEvent(rs), args.toArray());
    }

    private static LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private static AuditEvent toEvent(ResultSet rs) throws SQLException {
        return new AuditEvent(rs.getObject("event_time", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getString("event"), rs.getString("msisdn"), rs.getString("gateway"), rs.getString("actor"),
                rs.getString("session_id"), rs.getString("command_id"), rs.getString("reason"),
                rs.getObject("requested_mb", Long.class), rs.getObject("granted_mb", Long.class),
                rs.getObject("bandwidth_kbps", Integer.class), rs.getString("source"), rs.getString("package_code"));
    }
}
