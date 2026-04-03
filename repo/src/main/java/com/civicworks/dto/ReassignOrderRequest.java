package com.civicworks.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class ReassignOrderRequest {

    @NotNull(message = "newDriverId is required")
    private UUID newDriverId;

    @NotNull(message = "entityVersion is required to prevent concurrent modification")
    private Integer entityVersion;

    public UUID getNewDriverId() { return newDriverId; }
    public void setNewDriverId(UUID newDriverId) { this.newDriverId = newDriverId; }

    public Integer getEntityVersion() { return entityVersion; }
    public void setEntityVersion(Integer entityVersion) { this.entityVersion = entityVersion; }
}
