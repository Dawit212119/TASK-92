package com.civicworks.domain.entity;

import com.civicworks.domain.enums.TransitionType;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "content_publish_snapshots")
public class ContentPublishSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "content_item_id", nullable = false)
    private UUID contentItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transition_type", nullable = false)
    private TransitionType transitionType;

    @Column(name = "snapshot_title")
    private String snapshotTitle;

    @Column(name = "snapshot_body", columnDefinition = "TEXT")
    private String snapshotBody;

    @Column(name = "snapshot_tags")
    private String snapshotTags;

    @Column(name = "snapshot_scheduled_at")
    private OffsetDateTime snapshotScheduledAt;

    @Column(name = "snapshot_published_at")
    private OffsetDateTime snapshotPublishedAt;

    @Column(name = "snapshot_state")
    private String snapshotState;

    @Column(name = "snapshot_version")
    private Integer snapshotVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public ContentPublishSnapshot() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getContentItemId() { return contentItemId; }
    public void setContentItemId(UUID contentItemId) { this.contentItemId = contentItemId; }

    public TransitionType getTransitionType() { return transitionType; }
    public void setTransitionType(TransitionType transitionType) { this.transitionType = transitionType; }

    public String getSnapshotTitle() { return snapshotTitle; }
    public void setSnapshotTitle(String snapshotTitle) { this.snapshotTitle = snapshotTitle; }

    public String getSnapshotBody() { return snapshotBody; }
    public void setSnapshotBody(String snapshotBody) { this.snapshotBody = snapshotBody; }

    public String getSnapshotTags() { return snapshotTags; }
    public void setSnapshotTags(String snapshotTags) { this.snapshotTags = snapshotTags; }

    public OffsetDateTime getSnapshotScheduledAt() { return snapshotScheduledAt; }
    public void setSnapshotScheduledAt(OffsetDateTime snapshotScheduledAt) { this.snapshotScheduledAt = snapshotScheduledAt; }

    public OffsetDateTime getSnapshotPublishedAt() { return snapshotPublishedAt; }
    public void setSnapshotPublishedAt(OffsetDateTime snapshotPublishedAt) { this.snapshotPublishedAt = snapshotPublishedAt; }

    public String getSnapshotState() { return snapshotState; }
    public void setSnapshotState(String snapshotState) { this.snapshotState = snapshotState; }

    public Integer getSnapshotVersion() { return snapshotVersion; }
    public void setSnapshotVersion(Integer snapshotVersion) { this.snapshotVersion = snapshotVersion; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
