package com.civicworks.dto;

import com.civicworks.domain.enums.ContentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

public class CreateContentItemRequest {

    @NotNull
    private ContentType type;

    @NotBlank
    private String title;

    private String sanitizedBody;

    private List<String> tags;

    private OffsetDateTime scheduledAt;

    public ContentType getType() { return type; }
    public void setType(ContentType type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSanitizedBody() { return sanitizedBody; }
    public void setSanitizedBody(String sanitizedBody) { this.sanitizedBody = sanitizedBody; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public OffsetDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(OffsetDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
}
