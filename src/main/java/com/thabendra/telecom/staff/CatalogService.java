package com.thabendra.telecom.staff;

import com.thabendra.telecom.subscriber.BusinessRuleException;
import com.thabendra.telecom.subscriber.NotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The provider's catalog (PR-2): packages, their prices and speeds, and plan speeds. Every change keeps
 * who made it (updated_by, the staff login) and when; price changes are also copied to
 * data_package_price by a trigger (V9), so the price history can't be skipped.
 * <p>
 * A new speed is used from the next charging grant on (the CHARGING package reads it per grant);
 * gateways are not pushed a new speed for users who are online now.
 */
@Service
public class CatalogService {

    /** A package as staff see it: with whether it is on sale, and who changed it last. */
    public record CatalogPackage(String code, String name, String app, BigDecimal price, Long dataMb,
                                 int validityDays, int maxBandwidthKbps, Long fairUsageMb,
                                 Integer throttledBandwidthKbps, boolean active, String updatedBy,
                                 OffsetDateTime updatedAt) {
    }

    /** What a new package needs; app null = data any app can use, dataMb null = unlimited. */
    public record NewPackage(String code, String name, String app, BigDecimal price, Long dataMb, int validityDays,
                             int maxBandwidthKbps, Long fairUsageMb, Integer throttledBandwidthKbps) {
    }

    /** One price as it was set. oldPrice is null for the first price. */
    public record PriceChange(BigDecimal oldPrice, BigDecimal newPrice, String changedBy, OffsetDateTime changedAt) {
    }

    private static final String SELECT_PACKAGE = """
            SELECT code, name, app, price, data_mb, validity_days, max_bandwidth_kbps, fair_usage_mb,
                   throttled_bandwidth_kbps, active, updated_by, updated_at
              FROM data_package""";

    private final JdbcTemplate jdbc;

    public CatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<CatalogPackage> packages() {
        return jdbc.query(SELECT_PACKAGE + " ORDER BY code", (rs, i) -> toPackage(rs));
    }

    @Transactional(readOnly = true)
    public CatalogPackage find(String code) {
        return jdbc.query(SELECT_PACKAGE + " WHERE code = ?", (rs, i) -> toPackage(rs), code).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Package " + code + " not found"));
    }

    @Transactional
    public CatalogPackage create(NewPackage p, String by) {
        try {
            jdbc.update("""
                    INSERT INTO data_package (code, name, app, price, data_mb, validity_days, max_bandwidth_kbps,
                                              fair_usage_mb, throttled_bandwidth_kbps, updated_by, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, SYSTIMESTAMP)""",
                    p.code(), p.name(), p.app(), p.price(), p.dataMb(), p.validityDays(), p.maxBandwidthKbps(),
                    p.fairUsageMb(), p.throttledBandwidthKbps(), by);
        } catch (DuplicateKeyException e) {
            throw new BusinessRuleException("Package " + p.code() + " already exists");
        } catch (DataIntegrityViolationException e) {
            // the table's CHECK constraints, e.g. a fair usage limit without a throttled speed
            throw new BusinessRuleException("Package " + p.code() + " breaks a catalog rule: "
                    + "fair usage MB and throttled speed go together, sizes and prices can't be negative");
        }
        return find(p.code());
    }

    @Transactional
    public CatalogPackage changePrice(String code, BigDecimal price, String by) {
        update(code, "UPDATE data_package SET price = ?, updated_by = ?, updated_at = SYSTIMESTAMP WHERE code = ?",
                price, by, code);
        return find(code);
    }

    @Transactional
    public CatalogPackage changeSpeed(String code, int maxBandwidthKbps, Integer throttledBandwidthKbps, String by) {
        CatalogPackage current = find(code);
        if ((current.fairUsageMb() == null) != (throttledBandwidthKbps == null)) {
            throw new BusinessRuleException(current.fairUsageMb() == null
                    ? "Package " + code + " has no fair usage limit, so it has no throttled speed"
                    : "Package " + code + " has a fair usage limit: give its throttled speed too");
        }
        update(code, """
                UPDATE data_package
                   SET max_bandwidth_kbps = ?, throttled_bandwidth_kbps = ?, updated_by = ?, updated_at = SYSTIMESTAMP
                 WHERE code = ?""", maxBandwidthKbps, throttledBandwidthKbps, by, code);
        return find(code);
    }

    /** Stops or starts selling a package. Packages already bought keep working until they expire. */
    @Transactional
    public CatalogPackage setActive(String code, boolean active, String by) {
        update(code, "UPDATE data_package SET active = ?, updated_by = ?, updated_at = SYSTIMESTAMP WHERE code = ?",
                active ? "Y" : "N", by, code);
        return find(code);
    }

    @Transactional(readOnly = true)
    public List<PriceChange> priceHistory(String code) {
        find(code);
        return jdbc.query("""
                SELECT h.old_price, h.new_price, h.changed_by, h.changed_at
                  FROM data_package_price h
                  JOIN data_package p ON p.id = h.package_id
                 WHERE p.code = ?
                 ORDER BY h.changed_at, h.id""",
                (rs, i) -> new PriceChange(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class)),
                code);
    }

    /** A plan's top speed: what a user gets on plan data, and what UNBLOCK sends after a suspension. */
    @Transactional
    public void changePlanSpeed(String planCode, int maxBandwidthKbps, String by) {
        int rows = jdbc.update("""
                UPDATE plan SET max_bandwidth_kbps = ?, updated_by = ?, updated_at = SYSTIMESTAMP
                 WHERE code = ?""", maxBandwidthKbps, by, planCode);
        if (rows == 0) {
            throw new NotFoundException("Plan " + planCode + " not found");
        }
    }

    private void update(String code, String sql, Object... args) {
        if (jdbc.update(sql, args) == 0) {
            throw new NotFoundException("Package " + code + " not found");
        }
    }

    private static CatalogPackage toPackage(ResultSet rs) throws SQLException {
        return new CatalogPackage(rs.getString("code"), rs.getString("name"), rs.getString("app"),
                rs.getBigDecimal("price"), rs.getObject("data_mb", Long.class), rs.getInt("validity_days"),
                rs.getInt("max_bandwidth_kbps"), rs.getObject("fair_usage_mb", Long.class),
                rs.getObject("throttled_bandwidth_kbps", Integer.class), "Y".equals(rs.getString("active")),
                rs.getString("updated_by"), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
