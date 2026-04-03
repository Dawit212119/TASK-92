package com.civicworks.dto;

import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

public class UpdateContentItemRequest {

    private String title;
    private String sanitizedBody;
    private List<String> tags;
    private OffsetDateTime scheduledAt;

    /** Optimistic-locking version of the ContentItem. */
    @NotNull(message = "entityVersion is required to prevent concurrent modification")
    private Integer entityVersion;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSanitizedBody() { return sanitizedBody; }
    public void setSanitizedBody(String sanitizedBody) { this.sanitizedBody = sanitizedBody; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public OffsetDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(OffsetDateTime scheduledAt) { this.scheduledAt = scheduledAt; }

    public Integer getEntityVersion() { return entityVersion; }
    public void setEntityVersion(Integer entityVersion) { this.entityVersion = entityVersion; }
}
