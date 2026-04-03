package com.civicworks.domain.entity;

import com.civicworks.domain.enums.ContentState;
import com.civicworks.domain.enums.ContentType;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "content_items")
public class ContentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private ContentType type;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "sanitized_body", columnDefinition = "TEXT")
    private String sanitizedBody;

    @Convert(converter = StringListConverter.class)
    @Column(name = "tags", columnDefinition = "TEXT")
    private List<String> tags;

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private ContentState state = ContentState.DRAFT;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // search_vector is a generated TSVECTOR column - never write to it
    @Column(name = "search_vector", insertable = false, updatable = false,
            columnDefinition = "TSVECTOR")
    private String searchVector;

    /** Optional price in cents; used for price-range filter in search. */
    @Column(name = "price_cents")
    private Long priceCents;

    /** Optional geographic or administrative origin label (e.g. district, zone). */
    @Column(name = "origin", length = 255)
    private String origin;

    public ContentItem() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

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

    public OffsetDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(OffsetDateTime publishedAt) { this.publishedAt = publishedAt; }

    public ContentState getState() { return state; }
    public void setState(ContentState state) { this.state = state; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public Organization getOrganization() { return organization; }
    public void setOrganization(Organization organization) { this.organization = organization; }

    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getPriceCents() { return priceCents; }
    public void setPriceCents(Long priceCents) { this.priceCents = priceCents; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
}
