package com.civicworks.dto;

import com.civicworks.domain.enums.DiscrepancyStatus;
import jakarta.validation.constraints.NotNull;

public class ResolveDiscrepancyRequest {

    @NotNull
    private DiscrepancyStatus resolution;

    private String notes;

    public DiscrepancyStatus getResolution() { return resolution; }
    public void setResolution(DiscrepancyStatus resolution) { this.resolution = resolution; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
