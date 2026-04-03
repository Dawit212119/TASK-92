package com.civicworks.dto;

import com.civicworks.domain.enums.PaymentMethod;
import com.civicworks.domain.enums.SettlementMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;

import java.util.List;

public class CreateSettlementRequest {

    @NotNull(message = "settlementMode is required")
    private SettlementMode settlementMode;

    private String shiftId;

    // For FULL mode
    private PaymentMethod paymentMethod;

    // For SPLIT_EVEN mode
    @Min(value = 1, message = "splitCount must be at least 1")
    private int splitCount = 1;

    // For SPLIT_CUSTOM mode
    @Valid
    private List<PaymentAllocationEntry> allocations;

    /** Optimistic-locking version of the Bill being settled. Required — prevents last-write-wins. */
    @NotNull(message = "entityVersion is required to prevent concurrent modification")
    private Integer entityVersion;

    public SettlementMode getSettlementMode() { return settlementMode; }
    public void setSettlementMode(SettlementMode settlementMode) { this.settlementMode = settlementMode; }

    public String getShiftId() { return shiftId; }
    public void setShiftId(String shiftId) { this.shiftId = shiftId; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public int getSplitCount() { return splitCount; }
    public void setSplitCount(int splitCount) { this.splitCount = splitCount; }

    public List<PaymentAllocationEntry> getAllocations() { return allocations; }
    public void setAllocations(List<PaymentAllocationEntry> allocations) { this.allocations = allocations; }

    public Integer getEntityVersion() { return entityVersion; }
    public void setEntityVersion(Integer entityVersion) { this.entityVersion = entityVersion; }

    public static class PaymentAllocationEntry {
        @Min(value = 1, message = "payerSeq must be at least 1")
        private int payerSeq;

        @NotNull(message = "paymentMethod is required in each allocation")
        private PaymentMethod paymentMethod;

        @Positive(message = "amountCents must be positive")
        private long amountCents;

        public int getPayerSeq() { return payerSeq; }
        public void setPayerSeq(int payerSeq) { this.payerSeq = payerSeq; }

        public PaymentMethod getPaymentMethod() { return paymentMethod; }
        public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

        public long getAmountCents() { return amountCents; }
        public void setAmountCents(long amountCents) { this.amountCents = amountCents; }
    }
}
