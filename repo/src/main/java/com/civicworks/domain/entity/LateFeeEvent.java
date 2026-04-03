package com.civicworks.domain.entity;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "late_fee_events")
public class LateFeeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "bill_id", nullable = false, unique = true)
    private UUID billId;

    @Column(name = "late_fee_cents", nullable = false)
    private long lateFeeCents;

    @Column(name = "applied_at", nullable = false)
    private OffsetDateTime appliedAt = OffsetDateTime.now();

    public LateFeeEvent() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getBillId() { return billId; }
    public void setBillId(UUID billId) { this.billId = billId; }

    public long getLateFeeCents() { return lateFeeCents; }
    public void setLateFeeCents(long lateFeeCents) { this.lateFeeCents = lateFeeCents; }

    public OffsetDateTime getAppliedAt() { return appliedAt; }
    public void setAppliedAt(OffsetDateTime appliedAt) { this.appliedAt = appliedAt; }
}
