package com.civicworks.dto;

import com.civicworks.domain.entity.ShiftHandover;

import java.util.Map;
import java.util.UUID;

/**
 * Report-grade shift handover summary returned by the handover endpoint.
 * Includes the persisted handover, submitted vs posted totals by payment
 * method, the overall delta, and whether a discrepancy case was opened.
 */
public class ShiftHandoverSummary {

    private ShiftHandover handover;
    private Map<String, Long> submittedByMethod;
    private Map<String, Long> postedByMethod;
    private long totalSubmittedCents;
    private long totalPostedCents;
    private long deltaCents;
    private boolean discrepancyCreated;
    private UUID discrepancyCaseId;

    public ShiftHandoverSummary() {}

    public ShiftHandover getHandover() { return handover; }
    public void setHandover(ShiftHandover handover) { this.handover = handover; }

    public Map<String, Long> getSubmittedByMethod() { return submittedByMethod; }
    public void setSubmittedByMethod(Map<String, Long> submittedByMethod) { this.submittedByMethod = submittedByMethod; }

    public Map<String, Long> getPostedByMethod() { return postedByMethod; }
    public void setPostedByMethod(Map<String, Long> postedByMethod) { this.postedByMethod = postedByMethod; }

    public long getTotalSubmittedCents() { return totalSubmittedCents; }
    public void setTotalSubmittedCents(long totalSubmittedCents) { this.totalSubmittedCents = totalSubmittedCents; }

    public long getTotalPostedCents() { return totalPostedCents; }
    public void setTotalPostedCents(long totalPostedCents) { this.totalPostedCents = totalPostedCents; }

    public long getDeltaCents() { return deltaCents; }
    public void setDeltaCents(long deltaCents) { this.deltaCents = deltaCents; }

    public boolean isDiscrepancyCreated() { return discrepancyCreated; }
    public void setDiscrepancyCreated(boolean discrepancyCreated) { this.discrepancyCreated = discrepancyCreated; }

    public UUID getDiscrepancyCaseId() { return discrepancyCaseId; }
    public void setDiscrepancyCaseId(UUID discrepancyCaseId) { this.discrepancyCaseId = discrepancyCaseId; }
}
