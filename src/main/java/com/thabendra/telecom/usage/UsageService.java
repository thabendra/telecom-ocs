package com.thabendra.telecom.usage;

import com.thabendra.telecom.push.GatewayCommand.Reason;
import com.thabendra.telecom.push.GatewayCommands;
import com.thabendra.telecom.subscriber.BusinessRuleException;
import com.thabendra.telecom.subscriber.OracleErrors;
import com.thabendra.telecom.subscriber.Subscriber;
import com.thabendra.telecom.subscriber.SubscriberService;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Packages, quota and usage charging. The charging itself runs in the RECORD_USAGE PL/SQL procedure,
 * which plays the role of the operator's online charging system (OCS) and speed policy (PCRF).
 */
@Service
public class UsageService {

    /** App the network's DPI detected for the traffic. GENERAL = anything without its own package. */
    public enum App { YOUTUBE, WHATSAPP, FACEBOOK, GENERAL }

    /** The share of one usage event charged to one bucket. packageCode is null for PLAN and BALANCE. */
    public record UsagePortion(String source, String packageCode, long dataMb, BigDecimal moneyCharged) {
    }

    public record UsageResult(String msisdn, App app, long dataMb, int bandwidthKbps, BigDecimal moneyCharged,
                              BigDecimal balance, List<UsagePortion> portions) {
    }

    /** One place data can come from. dataMbLeft is null for unlimited; expiresAt is null for the plan. */
    public record Bucket(String source, String code, String name, String app, Long dataMbLeft, long dataMbUsed,
                         int bandwidthKbps, LocalDateTime expiresAt) {
    }

    /** Buckets are listed in the order RECORD_USAGE uses them (app packages, add-ons, then the plan). */
    public record Quota(String msisdn, BigDecimal balance, List<Bucket> buckets) {
    }

    public record ActivatedPackage(String msisdn, String packageCode, LocalDateTime expiresAt, BigDecimal balance) {
    }

    /** One row of the usage history (CU-2): one bucket's share of one usage event. */
    public record UsageItem(String usageRef, LocalDateTime recordedAt, String app, String source, String packageCode,
                            long dataMb, BigDecimal moneyCharged) {
    }

    /** A page of the history, newest first. */
    public record UsageHistory(String msisdn, LocalDateTime from, LocalDateTime to, int page, int size,
                               List<UsageItem> items) {
    }

    private final DataPackageRepository packages;
    private final SubscriberService subscribers;
    private final JdbcTemplate jdbc;
    private final GatewayCommands commands;
    private final SimpleJdbcCall activatePackageCall;
    private final SimpleJdbcCall recordUsageCall;

    public UsageService(DataPackageRepository packages, SubscriberService subscribers, JdbcTemplate jdbc,
                        GatewayCommands commands) {
        this.packages = packages;
        this.subscribers = subscribers;
        this.jdbc = jdbc;
        this.commands = commands;
        this.activatePackageCall = new SimpleJdbcCall(jdbc)
                .withProcedureName("ACTIVATE_PACKAGE")
                .withoutProcedureColumnMetaDataAccess()
                .declareParameters(
                        new SqlParameter("P_MSISDN", Types.VARCHAR),
                        new SqlParameter("P_PACKAGE_CODE", Types.VARCHAR),
                        new SqlOutParameter("P_SUBSCRIBER_PACKAGE_ID", Types.NUMERIC));
        this.recordUsageCall = new SimpleJdbcCall(jdbc)
                .withProcedureName("RECORD_USAGE")
                .withoutProcedureColumnMetaDataAccess()
                .declareParameters(
                        new SqlParameter("P_MSISDN", Types.VARCHAR),
                        new SqlParameter("P_APP", Types.VARCHAR),
                        new SqlParameter("P_DATA_MB", Types.NUMERIC),
                        new SqlParameter("P_USAGE_REF", Types.VARCHAR),
                        new SqlOutParameter("P_BANDWIDTH_KBPS", Types.NUMERIC),
                        new SqlOutParameter("P_MONEY_CHARGED", Types.NUMERIC));
    }

    /** The packages on sale; one the provider stopped selling (PR-2) is no longer listed. */
    @Transactional(readOnly = true)
    public List<DataPackage> listPackages() {
        return packages.findByActive("Y");
    }

    @Transactional
    public ActivatedPackage activatePackage(String msisdn, String packageCode) {
        Map<String, Object> out;
        try {
            out = activatePackageCall.execute(Map.of("P_MSISDN", msisdn, "P_PACKAGE_CODE", packageCode));
        } catch (DataAccessException e) {
            throw OracleErrors.translate(e);
        }
        long id = ((Number) out.get("P_SUBSCRIBER_PACKAGE_ID")).longValue();
        LocalDateTime expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM subscriber_package WHERE id = ?", LocalDateTime.class, id);
        // PU-2: blocked for running out? The new package lets the user through again at its speed
        Integer packageKbps = jdbc.queryForObject(
                "SELECT max_bandwidth_kbps FROM data_package WHERE code = ?", Integer.class, packageCode);
        commands.unblockIfOutOfQuota(msisdn, Reason.PACKAGE, packageKbps);
        return new ActivatedPackage(msisdn, packageCode, expiresAt, balanceOf(msisdn));
    }

    @Transactional
    public UsageResult recordUsage(String msisdn, App app, long dataMb) {
        String usageRef = UUID.randomUUID().toString();
        Map<String, Object> out;
        try {
            out = recordUsageCall.execute(Map.of(
                    "P_MSISDN", msisdn, "P_APP", app.name(), "P_DATA_MB", dataMb, "P_USAGE_REF", usageRef));
        } catch (DataAccessException e) {
            throw OracleErrors.translate(e);
        }
        List<UsagePortion> portions = jdbc.query("""
                SELECT ur.source, dp.code, ur.data_mb, ur.money_charged
                  FROM usage_record ur
                  LEFT JOIN subscriber_package sp ON sp.id = ur.subscriber_package_id
                  LEFT JOIN data_package dp ON dp.id = sp.package_id
                 WHERE ur.usage_ref = ?
                 ORDER BY ur.id""",
                (rs, i) -> new UsagePortion(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getBigDecimal(4)),
                usageRef);
        return new UsageResult(msisdn, app, dataMb,
                ((Number) out.get("P_BANDWIDTH_KBPS")).intValue(),
                (BigDecimal) out.get("P_MONEY_CHARGED"),
                balanceOf(msisdn), portions);
    }

    @Transactional(readOnly = true)
    public Quota getQuota(String msisdn) {
        Subscriber subscriber = subscribers.getSubscriber(msisdn);
        List<Bucket> buckets = new ArrayList<>(jdbc.query("""
                SELECT dp.code, dp.name, dp.app, dp.data_mb, sp.data_mb_used, sp.data_mb_reserved, dp.max_bandwidth_kbps,
                       dp.fair_usage_mb, dp.throttled_bandwidth_kbps, sp.expires_at
                  FROM subscriber_package sp
                  JOIN data_package dp ON dp.id = sp.package_id
                 WHERE sp.subscriber_id = ?
                   AND sp.expires_at > SYSTIMESTAMP
                 ORDER BY CASE WHEN dp.app IS NULL THEN 1 ELSE 0 END, sp.expires_at""",
                (rs, i) -> {
                    long used = rs.getLong("data_mb_used");
                    long dataMb = rs.getLong("data_mb");
                    // MB held for open charging sessions isn't available
                    Long left = rs.wasNull() ? null : Math.max(0, dataMb - used - rs.getLong("data_mb_reserved"));
                    long fairUsage = rs.getLong("fair_usage_mb");
                    boolean throttled = !rs.wasNull() && used >= fairUsage;
                    int bandwidth = throttled ? rs.getInt("throttled_bandwidth_kbps") : rs.getInt("max_bandwidth_kbps");
                    return new Bucket("PACKAGE", rs.getString("code"), rs.getString("name"), rs.getString("app"),
                            left, used, bandwidth, rs.getObject("expires_at", LocalDateTime.class));
                },
                subscriber.getId()));
        long planLeft = subscriber.getPlanDataLeftMb();
        buckets.add(new Bucket("PLAN", subscriber.getPlan().getCode(), subscriber.getPlan().getName(), null,
                planLeft, subscriber.getPlanDataUsedMb(),
                subscriber.getPlan().getMaxBandwidthKbps(), null));
        return new Quota(msisdn, subscriber.getBalance(), buckets);
    }

    /**
     * CU-2: what the customer used from {@code from} (inclusive) to {@code to} (exclusive; null = no end),
     * by recorded_at, newest first, one page at a time.
     */
    @Transactional(readOnly = true)
    public UsageHistory history(String msisdn, LocalDateTime from, LocalDateTime to, int page, int size) {
        if (to != null && !from.isBefore(to)) {
            throw new BusinessRuleException("from must be before to");
        }
        Subscriber subscriber = subscribers.getSubscriber(msisdn);
        List<Object> args = new ArrayList<>(List.of(subscriber.getId(), from));
        if (to != null) {
            args.add(to);
        }
        args.add((long) page * size);
        args.add(size);
        String until = to != null ? " AND ur.recorded_at < ?" : "";
        List<UsageItem> items = jdbc.query("""
                SELECT ur.usage_ref, ur.recorded_at, ur.app, ur.source, dp.code, ur.data_mb, ur.money_charged
                  FROM usage_record ur
                  LEFT JOIN subscriber_package sp ON sp.id = ur.subscriber_package_id
                  LEFT JOIN data_package dp ON dp.id = sp.package_id
                 WHERE ur.subscriber_id = ?
                   AND ur.recorded_at >= ?%s
                 ORDER BY ur.recorded_at DESC, ur.id DESC
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY""".formatted(until),
                (rs, i) -> new UsageItem(rs.getString(1), rs.getObject(2, LocalDateTime.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getBigDecimal(7)),
                args.toArray());
        return new UsageHistory(msisdn, from, to, page, size, items);
    }

    private BigDecimal balanceOf(String msisdn) {
        return jdbc.queryForObject("SELECT balance FROM subscriber WHERE msisdn = ?", BigDecimal.class, msisdn);
    }
}
