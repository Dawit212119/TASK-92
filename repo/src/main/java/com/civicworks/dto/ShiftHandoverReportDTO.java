package com.civicworks.dto;

import com.civicworks.domain.entity.ShiftHandover;

import java.util.Map;
import java.util.UUID;

/**
 * Report-grade shift handover data returned by the handover endpoint.
 * Contains explicit named totals by payment channel, overall submitted/expected
 * comparison, delta, and discrepancy flag — ready for audit consumption.
 */
public class ShiftHandoverReportDTO {

    private UUID handoverId;
    private String shiftId;
    private String status;

    // Named payment-method totals (submitted by clerk)
    private long totalCash;
    private long totalCheck;
    private long totalVoucher;
    private long totalOther;

    // Aggregates
    private long submittedTotal;
    private long expectedTotal;
    private long delta;
    private boolean discrepancyFlag;
    private UUID discrepancyCaseId;

    // Full breakdowns for audit trail
    private Map<String, Long> submittedByMethod;
    private Map<String, Long> postedByMethod;

    public ShiftHandoverReportDTO() {}

    // ── getters / setters ────────────────────────────────────────────────

    public UUID getHandoverId() { return handoverId; }
    public void setHandoverId(UUID handoverId) { this.handoverId = handoverId; }

    public String getShiftId() { return shiftId; }
    public void setShiftId(String shiftId) { this.shiftId = shiftId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getTotalCash() { return totalCash; }
    public void setTotalCash(long totalCash) { this.totalCash = totalCash; }

    public long getTotalCheck() { return totalCheck; }
    public void setTotalCheck(long totalCheck) { this.totalCheck = totalCheck; }

    public long getTotalVoucher() { return totalVoucher; }
    public void setTotalVoucher(long totalVoucher) { this.totalVoucher = totalVoucher; }

    public long getTotalOther() { return totalOther; }
    public void setTotalOther(long totalOther) { this.totalOther = totalOther; }

    public long getSubmittedTotal() { return submittedTotal; }
    public void setSubmittedTotal(long submittedTotal) { this.submittedTotal = submittedTotal; }

    public long getExpectedTotal() { return expectedTotal; }
    public void setExpectedTotal(long expectedTotal) { this.expectedTotal = expectedTotal; }

    public long getDelta() { return delta; }
    public void setDelta(long delta) { this.delta = delta; }

    public boolean isDiscrepancyFlag() { return discrepancyFlag; }
    public void setDiscrepancyFlag(boolean discrepancyFlag) { this.discrepancyFlag = discrepancyFlag; }

    public UUID getDiscrepancyCaseId() { return discrepancyCaseId; }
    public void setDiscrepancyCaseId(UUID discrepancyCaseId) { this.discrepancyCaseId = discrepancyCaseId; }

    public Map<String, Long> getSubmittedByMethod() { return submittedByMethod; }
    public void setSubmittedByMethod(Map<String, Long> submittedByMethod) { this.submittedByMethod = submittedByMethod; }

    public Map<String, Long> getPostedByMethod() { return postedByMethod; }
    public void setPostedByMethod(Map<String, Long> postedByMethod) { this.postedByMethod = postedByMethod; }
}
