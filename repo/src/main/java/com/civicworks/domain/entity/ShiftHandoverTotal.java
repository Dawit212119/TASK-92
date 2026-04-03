package com.civicworks.domain.entity;

import com.civicworks.domain.enums.PaymentMethod;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "shift_handover_totals")
public class ShiftHandoverTotal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "handover_id", nullable = false)
    private UUID handoverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod;

    @Column(name = "total_amount_cents", nullable = false)
    private long totalAmountCents;

    public ShiftHandoverTotal() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getHandoverId() { return handoverId; }
    public void setHandoverId(UUID handoverId) { this.handoverId = handoverId; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public long getTotalAmountCents() { return totalAmountCents; }
    public void setTotalAmountCents(long totalAmountCents) { this.totalAmountCents = totalAmountCents; }
}
