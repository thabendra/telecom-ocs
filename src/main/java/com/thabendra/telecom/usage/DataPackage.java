package com.thabendra.telecom.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** A package a subscriber can buy on top of their plan (YouTube Unlimited, Extra 25GB, ...). */
@Entity
@Table(name = "data_package")
public class DataPackage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 60)
    private String name;

    // null = add-on data any app can use
    @Column(length = 20)
    private String app;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    // null = unlimited
    @Column(name = "data_mb")
    private Long dataMb;

    @Column(name = "validity_days", nullable = false)
    private Integer validityDays;

    @Column(name = "max_bandwidth_kbps", nullable = false)
    private Integer maxBandwidthKbps;

    @Column(name = "fair_usage_mb")
    private Long fairUsageMb;

    @Column(name = "throttled_bandwidth_kbps")
    private Integer throttledBandwidthKbps;

    protected DataPackage() {
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getApp() {
        return app;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Long getDataMb() {
        return dataMb;
    }

    public Integer getValidityDays() {
        return validityDays;
    }

    public Integer getMaxBandwidthKbps() {
        return maxBandwidthKbps;
    }

    public Long getFairUsageMb() {
        return fairUsageMb;
    }

    public Integer getThrottledBandwidthKbps() {
        return throttledBandwidthKbps;
    }
}
