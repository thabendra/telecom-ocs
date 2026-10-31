package com.thabendra.telecom.charging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.NamedStoredProcedureQueries;
import jakarta.persistence.NamedStoredProcedureQuery;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.StoredProcedureParameter;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * One online charging session (gateway -> backend). Rows are written only by the CHARGING PL/SQL
 * package (V7), so the entity is read-only for JPA. The package's procedures are declared here
 * so {@link ChargingSessionRepository} can call them with {@code @Procedure} and get every OUT
 * parameter back in a Map.
 */
@Entity
@Table(name = "charging_session")
@Immutable
@NamedStoredProcedureQueries({
        @NamedStoredProcedureQuery(
                name = "ChargingSession.startSession",
                procedureName = "CHARGING.START_SESSION",
                parameters = {
                        @StoredProcedureParameter(mode = ParameterMode.IN, name = "P_SESSION_ID", type = String.class),
                        @StoredProcedureParameter(mode = ParameterMode.IN, name = "P_MSISDN", type = String.class),
                        @StoredProcedureParameter(mode = ParameterMode.IN, name = "P_APP", type = String.class),
                        @StoredProcedureParameter(mode = ParameterMode.IN, name = "P_REQUESTED_MB", type = Long.class),
                        @StoredProcedureParameter(mode = ParameterMode.OUT, name = "P_GRANTED_MB", type = Long.class),
                        @StoredProcedureParameter(mode = ParameterMode.OUT, name = "P_BANDWIDTH_KBPS", type = Integer.class),
                        @StoredProcedureParameter(mode = ParameterMode.OUT, name = "P_SOURCE", type = String.class),
                        @StoredProcedureParameter(mode = ParameterMode.OUT, name = "P_PACKAGE_CODE", type = String.class)
                }),
        @NamedStoredProcedureQuery(
                name = "ChargingSession.updateSession",
                procedureName = "CHARGING.UPDATE_SESSION",
                parameters = {
                        @StoredProcedureParameter(mode = ParameterMode.IN, name = "P_SESSION_ID", type = String.class),
                        @StoredProcedureParameter(mode = ParameterMode.IN, name = "P_REQUEST_NUMBER", type = Long.class),
                        @StoredProcedureParameter(mode = ParameterMode.IN, name = "P_USED_MB", type = Long.class),
                        @StoredProcedureParameter(mode = ParameterMode.IN, name = "P_REQUESTED_MB", type = Long.class),
                        @StoredProcedureParameter(mode = ParameterMode.OUT, name = "P_GRANTED_MB", type = Long.class),
                        @StoredProcedureParameter(mode = ParameterMode.OUT, name = "P_BANDWIDTH_KBPS", type = Integer.class),
                        @StoredProcedureParameter(mode = ParameterMode.OUT, name = "P_SOURCE", type = String.class),
                        @StoredProcedureParameter(mode = ParameterMode.OUT, name = "P_PACKAGE_CODE", type = String.class)
                }),
        @NamedStoredProcedureQuery(
                name = "ChargingSession.releaseSession",
                procedureName = "CHARGING.RELEASE_SESSION",
                parameters = {
                        @StoredProcedureParameter(mode = ParameterMode.IN, name = "P_SESSION_ID", type = String.class),
                        @StoredProcedureParameter(mode = ParameterMode.IN, name = "P_REQUEST_NUMBER", type = Long.class),
                        @StoredProcedureParameter(mode = ParameterMode.IN, name = "P_USED_MB", type = Long.class),
                        @StoredProcedureParameter(mode = ParameterMode.OUT, name = "P_TOTAL_USED_MB", type = Long.class)
                })
})
public class ChargingSession {

    public enum Status { OPEN, RELEASED }

    @Id
    private String id;

    @Column(name = "subscriber_id")
    private Long subscriberId;

    private String app;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "total_used_mb")
    private Long totalUsedMb;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    protected ChargingSession() {
    }

    public String getId() {
        return id;
    }

    public Long getSubscriberId() {
        return subscriberId;
    }

    public String getApp() {
        return app;
    }

    public Status getStatus() {
        return status;
    }

    public Long getTotalUsedMb() {
        return totalUsedMb;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }
}
