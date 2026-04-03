package com.civicworks.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "driver_online_sessions")
public class DriverOnlineSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(name = "session_start", nullable = false)
    private OffsetDateTime sessionStart;

    @Column(name = "session_end")
    private OffsetDateTime sessionEnd;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public DriverOnlineSession() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getDriverId() { return driverId; }
    public void setDriverId(UUID driverId) { this.driverId = driverId; }

    public OffsetDateTime getSessionStart() { return sessionStart; }
    public void setSessionStart(OffsetDateTime sessionStart) { this.sessionStart = sessionStart; }

    public OffsetDateTime getSessionEnd() { return sessionEnd; }
    public void setSessionEnd(OffsetDateTime sessionEnd) { this.sessionEnd = sessionEnd; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
