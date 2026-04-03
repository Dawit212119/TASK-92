package com.civicworks.domain.entity;

import com.civicworks.domain.enums.OrderMode;
import com.civicworks.domain.enums.OrderStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "dispatch_orders")
public class DispatchOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "zone_id", nullable = false)
    private UUID zoneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false)
    private OrderMode mode = OrderMode.GRAB;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "assigned_driver_id")
    private UUID assignedDriverId;

    @Column(name = "forced_flag", nullable = false)
    private boolean forcedFlag = false;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "pickup_lat", precision = 10, scale = 7)
    private BigDecimal pickupLat;

    @Column(name = "pickup_lng", precision = 10, scale = 7)
    private BigDecimal pickupLng;

    @Column(name = "dropoff_lat", precision = 10, scale = 7)
    private BigDecimal dropoffLat;

    @Column(name = "dropoff_lng", precision = 10, scale = 7)
    private BigDecimal dropoffLng;

    @Column(name = "assigned_at")
    private OffsetDateTime assignedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public DispatchOrder() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getZoneId() { return zoneId; }
    public void setZoneId(UUID zoneId) { this.zoneId = zoneId; }

    public OrderMode getMode() { return mode; }
    public void setMode(OrderMode mode) { this.mode = mode; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public UUID getAssignedDriverId() { return assignedDriverId; }
    public void setAssignedDriverId(UUID assignedDriverId) { this.assignedDriverId = assignedDriverId; }

    public boolean isForcedFlag() { return forcedFlag; }
    public void setForcedFlag(boolean forcedFlag) { this.forcedFlag = forcedFlag; }

    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public BigDecimal getPickupLat() { return pickupLat; }
    public void setPickupLat(BigDecimal pickupLat) { this.pickupLat = pickupLat; }

    public BigDecimal getPickupLng() { return pickupLng; }
    public void setPickupLng(BigDecimal pickupLng) { this.pickupLng = pickupLng; }

    public BigDecimal getDropoffLat() { return dropoffLat; }
    public void setDropoffLat(BigDecimal dropoffLat) { this.dropoffLat = dropoffLat; }

    public BigDecimal getDropoffLng() { return dropoffLng; }
    public void setDropoffLng(BigDecimal dropoffLng) { this.dropoffLng = dropoffLng; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public OffsetDateTime getAssignedAt() { return assignedAt; }
    public void setAssignedAt(OffsetDateTime assignedAt) { this.assignedAt = assignedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
