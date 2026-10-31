package com.thabendra.telecom.subscriber;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "subscriber")
public class Subscriber {

    public enum Status { ACTIVE, SUSPENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // mobile number in international format without "+", e.g. 94771234567
    private String msisdn;

    @Column(name = "full_name")
    private String fullName;

    private String nic;

    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "plan_id")
    private Plan plan;

    // only the PL/SQL procedures change this, so JPA never writes it
    @Column(name = "plan_data_used_mb", insertable = false, updatable = false)
    private Long planDataUsedMb;

    @Column(name = "plan_data_reserved_mb", insertable = false, updatable = false)
    private Long planDataReservedMb;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Subscriber() {
    }

    public Subscriber(String msisdn, String fullName, String nic, BigDecimal balance, Plan plan) {
        this.msisdn = msisdn;
        this.fullName = fullName;
        this.nic = nic;
        this.balance = balance;
        this.plan = plan;
    }

    public void topUp(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    public void suspend() {
        this.status = Status.SUSPENDED;
    }

    public void activate() {
        this.status = Status.ACTIVE;
    }

    public Long getId() {
        return id;
    }

    public long getPlanDataUsedMb() {
        return planDataUsedMb == null ? 0 : planDataUsedMb;
    }

    // MB held for open charging sessions isn't available
    public long getPlanDataLeftMb() {
        long reserved = planDataReservedMb == null ? 0 : planDataReservedMb;
        return Math.max(0, plan.getDataMb() - getPlanDataUsedMb() - reserved);
    }

    public String getMsisdn() {
        return msisdn;
    }

    public String getFullName() {
        return fullName;
    }

    public String getNic() {
        return nic;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Status getStatus() {
        return status;
    }

    public Plan getPlan() {
        return plan;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
