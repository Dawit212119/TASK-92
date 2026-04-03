package com.civicworks.domain.entity;

import com.civicworks.domain.enums.DiscountType;
import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "bill_discounts")
public class BillDiscount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "bill_id", nullable = false, unique = true)
    private UUID billId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false)
    private DiscountType discountType;

    @Column(name = "value_basis_points_or_cents", nullable = false)
    private long valueBasisPointsOrCents;

    @Column(name = "applied_at", nullable = false)
    private OffsetDateTime appliedAt = OffsetDateTime.now();

    public BillDiscount() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getBillId() { return billId; }
    public void setBillId(UUID billId) { this.billId = billId; }

    public DiscountType getDiscountType() { return discountType; }
    public void setDiscountType(DiscountType discountType) { this.discountType = discountType; }

    public long getValueBasisPointsOrCents() { return valueBasisPointsOrCents; }
    public void setValueBasisPointsOrCents(long valueBasisPointsOrCents) { this.valueBasisPointsOrCents = valueBasisPointsOrCents; }

    public OffsetDateTime getAppliedAt() { return appliedAt; }
    public void setAppliedAt(OffsetDateTime appliedAt) { this.appliedAt = appliedAt; }
}
