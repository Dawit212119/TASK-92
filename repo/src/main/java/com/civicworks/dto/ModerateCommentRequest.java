package com.civicworks.dto;

import com.civicworks.domain.enums.ModerationState;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class ModerateCommentRequest {

    /** Moderation action: APPROVED, FLAGGED, HOLD, or REJECTED */
    @NotNull(message = "action is required")
    @JsonAlias("moderationState")
    private ModerationState action;

    @Size(max = 1000, message = "moderatorNotes must not exceed 1000 characters")
    @JsonAlias("reason")
    private String moderatorNotes;

    // entity_version for optimistic locking
    private Integer entityVersion;

    public ModerationState getAction() { return action; }
    public void setAction(ModerationState action) { this.action = action; }

    // Alias kept for backward compatibility with service layer
    public ModerationState getModerationState() { return action; }

    public String getModeratorNotes() { return moderatorNotes; }
    public void setModeratorNotes(String moderatorNotes) { this.moderatorNotes = moderatorNotes; }

    public String getReason() { return moderatorNotes; }

    public Integer getEntityVersion() { return entityVersion; }
    public void setEntityVersion(Integer entityVersion) { this.entityVersion = entityVersion; }
}
