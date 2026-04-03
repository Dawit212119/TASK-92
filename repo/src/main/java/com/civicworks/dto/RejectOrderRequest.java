package com.civicworks.dto;

import com.civicworks.domain.enums.RejectionReason;
import jakarta.validation.constraints.NotNull;

public class RejectOrderRequest {

    @NotNull(message = "rejectionReason is required")
    private RejectionReason rejectionReason;

    /** Optimistic-locking version of the DispatchOrder. */
    @NotNull(message = "entityVersion is required to prevent concurrent modification")
    private Integer entityVersion;

    public RejectionReason getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(RejectionReason rejectionReason) { this.rejectionReason = rejectionReason; }

    public Integer getEntityVersion() { return entityVersion; }
    public void setEntityVersion(Integer entityVersion) { this.entityVersion = entityVersion; }
}
