package com.civicworks.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Records the metered or per-unit quantity consumed by an account for a specific
 * fee item in a billing period.  Used by {@code BillingService.computeBillAmount}
 * to calculate PER_UNIT and METERED bill line amounts.
 */
@Entity
@Table(name = "usage_records",
       uniqueConstraints = @UniqueConstraint(columnNames = {"account_id", "fee_item_id", "billing_period"}))
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "fee_item_id", nullable = false)
    private UUID feeItemId;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "billing_period", nullable = false)
    private LocalDate billingPeriod;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public UsageRecord() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }

    public UUID getFeeItemId() { return feeItemId; }
    public void setFeeItemId(UUID feeItemId) { this.feeItemId = feeItemId; }

    public long getQuantity() { return quantity; }
    public void setQuantity(long quantity) { this.quantity = quantity; }

    public LocalDate getBillingPeriod() { return billingPeriod; }
    public void setBillingPeriod(LocalDate billingPeriod) { this.billingPeriod = billingPeriod; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
