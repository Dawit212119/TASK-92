package com.civicworks.dto;

import com.civicworks.domain.enums.BillingCycle;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public class CreateBillingRunRequest {

    @NotNull
    private LocalDate cycleDate;

    @NotNull
    private BillingCycle billingCycle;

    private String idempotencyKey;

    public LocalDate getCycleDate() { return cycleDate; }
    public void setCycleDate(LocalDate cycleDate) { this.cycleDate = cycleDate; }

    public BillingCycle getBillingCycle() { return billingCycle; }
    public void setBillingCycle(BillingCycle billingCycle) { this.billingCycle = billingCycle; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
