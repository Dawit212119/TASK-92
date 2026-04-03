package com.civicworks.dto;

import com.civicworks.domain.enums.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public class ShiftHandoverRequest {

    @NotNull(message = "submittedTotals is required")
    @NotEmpty(message = "submittedTotals must not be empty")
    @Valid
    private List<MethodTotal> submittedTotals;

    public List<MethodTotal> getSubmittedTotals() { return submittedTotals; }
    public void setSubmittedTotals(List<MethodTotal> submittedTotals) { this.submittedTotals = submittedTotals; }

    public static class MethodTotal {

        @NotNull(message = "paymentMethod is required")
        private PaymentMethod paymentMethod;

        @PositiveOrZero(message = "totalAmountCents must be zero or positive")
        private long totalAmountCents;

        public PaymentMethod getPaymentMethod() { return paymentMethod; }
        public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

        public long getTotalAmountCents() { return totalAmountCents; }
        public void setTotalAmountCents(long totalAmountCents) { this.totalAmountCents = totalAmountCents; }
    }
}
