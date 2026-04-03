package com.civicworks.domain.entity;

import com.civicworks.domain.enums.SettlementMode;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "settlements")
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "bill_id", nullable = false)
    private UUID billId;

    @Column(name = "shift_id")
    private String shiftId;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_mode", nullable = false)
    private SettlementMode settlementMode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Settlement() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getBillId() { return billId; }
    public void setBillId(UUID billId) { this.billId = billId; }

    public String getShiftId() { return shiftId; }
    public void setShiftId(String shiftId) { this.shiftId = shiftId; }

    public SettlementMode getSettlementMode() { return settlementMode; }
    public void setSettlementMode(SettlementMode settlementMode) { this.settlementMode = settlementMode; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
