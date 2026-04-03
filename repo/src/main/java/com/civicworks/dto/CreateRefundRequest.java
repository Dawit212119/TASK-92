package com.civicworks.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public class CreateRefundRequest {

    @NotNull(message = "refundAmountCents is required")
    @Positive(message = "refundAmountCents must be positive")
    private Long refundAmountCents;

    @Size(max = 500, message = "reason must not exceed 500 characters")
    private String reason;

    /** Optimistic-locking version of the originating Payment. Required — prevents last-write-wins. */
    @NotNull(message = "entityVersion is required to prevent concurrent modification")
    private Integer entityVersion;

    public Long getRefundAmountCents() { return refundAmountCents; }
    public void setRefundAmountCents(Long refundAmountCents) { this.refundAmountCents = refundAmountCents; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Integer getEntityVersion() { return entityVersion; }
    public void setEntityVersion(Integer entityVersion) { this.entityVersion = entityVersion; }
}
