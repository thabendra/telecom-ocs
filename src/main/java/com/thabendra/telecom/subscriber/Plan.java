package com.thabendra.telecom.subscriber;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "plan")
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    @Column(name = "monthly_fee")
    private BigDecimal monthlyFee;

    @Column(name = "data_gb")
    private Integer dataGb;

    @Column(name = "voice_minutes")
    private Integer voiceMinutes;

    @Column(name = "max_bandwidth_kbps")
    private Integer maxBandwidthKbps;

    protected Plan() {
    }

    public Plan(String code, String name, BigDecimal monthlyFee, int dataGb, int voiceMinutes, int maxBandwidthKbps) {
        this.code = code;
        this.name = name;
        this.monthlyFee = monthlyFee;
        this.dataGb = dataGb;
        this.voiceMinutes = voiceMinutes;
        this.maxBandwidthKbps = maxBandwidthKbps;
    }

    public long getDataMb() {
        return dataGb * 1024L;
    }

    public Integer getMaxBandwidthKbps() {
        return maxBandwidthKbps;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getMonthlyFee() {
        return monthlyFee;
    }

    public Integer getDataGb() {
        return dataGb;
    }

    public Integer getVoiceMinutes() {
        return voiceMinutes;
    }
}
