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
    @Column(nullable = false, unique = true, length = 11)
    private String msisdn;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(nullable = false, length = 12)
    private String nic;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    // only the PL/SQL procedures change this, so JPA never writes it
    @Column(name = "plan_data_used_mb", nullable = false, insertable = false, updatable = false)
    private Long planDataUsedMb;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
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

    public Long getId() {
        return id;
    }

    public long getPlanDataLeftMb() {
        long used = planDataUsedMb == null ? 0 : planDataUsedMb;
        return Math.max(0, plan.getDataMb() - used);
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
