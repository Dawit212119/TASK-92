package com.civicworks.domain.entity;

import com.civicworks.domain.enums.ModerationState;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "comments")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "content_item_id", nullable = false)
    private UUID contentItemId;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "content_text", nullable = false, columnDefinition = "TEXT")
    private String contentText;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_state", nullable = false)
    private ModerationState moderationState = ModerationState.APPROVED;

    @Column(name = "filter_hit_count", nullable = false)
    private Integer filterHitCount = 0;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Comment() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getContentItemId() { return contentItemId; }
    public void setContentItemId(UUID contentItemId) { this.contentItemId = contentItemId; }

    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }

    public UUID getAuthorId() { return authorId; }
    public void setAuthorId(UUID authorId) { this.authorId = authorId; }

    public String getContentText() { return contentText; }
    public void setContentText(String contentText) { this.contentText = contentText; }

    public ModerationState getModerationState() { return moderationState; }
    public void setModerationState(ModerationState moderationState) { this.moderationState = moderationState; }

    public Integer getFilterHitCount() { return filterHitCount; }
    public void setFilterHitCount(Integer filterHitCount) { this.filterHitCount = filterHitCount; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
