package com.civicworks.service;

import com.civicworks.domain.entity.ContentItem;
import com.civicworks.domain.entity.ContentPublishSnapshot;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.ContentState;
import com.civicworks.domain.enums.Role;
import com.civicworks.domain.enums.TransitionType;
import com.civicworks.dto.CreateContentItemRequest;
import com.civicworks.dto.UpdateContentItemRequest;
import com.civicworks.exception.BusinessException;
import com.civicworks.exception.ResourceNotFoundException;
import com.civicworks.exception.VersionConflictException;
import com.civicworks.repository.ContentItemRepository;
import com.civicworks.repository.ContentPublishSnapshotRepository;
import com.civicworks.scheduler.QuartzSchedulerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ContentService {

    private static final Logger log = LoggerFactory.getLogger(ContentService.class);

    private final ContentItemRepository contentItemRepository;
    private final ContentPublishSnapshotRepository snapshotRepository;
    private final AuditService auditService;
    private final QuartzSchedulerConfig quartzSchedulerConfig;
    private final HtmlSanitizerService htmlSanitizer;
    private final NotificationService notificationService;

    public ContentService(ContentItemRepository contentItemRepository,
                          ContentPublishSnapshotRepository snapshotRepository,
                          AuditService auditService,
                          QuartzSchedulerConfig quartzSchedulerConfig,
                          HtmlSanitizerService htmlSanitizer,
                          NotificationService notificationService) {
        this.contentItemRepository = contentItemRepository;
        this.snapshotRepository = snapshotRepository;
        this.auditService = auditService;
        this.quartzSchedulerConfig = quartzSchedulerConfig;
        this.htmlSanitizer = htmlSanitizer;
        this.notificationService = notificationService;
    }

    @Transactional
    public ContentItem createItem(CreateContentItemRequest request, User actor) {
        ContentItem item = new ContentItem();
        item.setType(request.getType());
        item.setTitle(request.getTitle());
        item.setSanitizedBody(htmlSanitizer.sanitize(request.getSanitizedBody()));
        item.setTags(request.getTags());
        item.setOrganization(actor.getOrganization());
        item.setCreatedBy(actor);

        if (request.getScheduledAt() != null) {
            item.setScheduledAt(request.getScheduledAt());
            item.setState(ContentState.SCHEDULED);
        } else {
            item.setState(ContentState.DRAFT);
        }

        ContentItem saved = contentItemRepository.save(item);

        if (saved.getState() == ContentState.SCHEDULED) {
            try {
                quartzSchedulerConfig.scheduleContentPublishJob(saved.getId(), saved.getScheduledAt());
            } catch (Exception e) {
                throw new BusinessException("Failed to schedule content publish job", HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public Page<ContentItem> findAll(String type, String state, String q, Pageable pageable, User actor) {
        UUID orgId = resolveOrgId(actor);
        if (q != null && !q.isBlank()) {
            List<ContentItem> results = orgId != null
                    ? contentItemRepository.fullTextSearchByOrg(orgId, q, state, type)
                    : contentItemRepository.fullTextSearch(q, state, type);
            int start = (int) pageable.getOffset();
            int end = Math.min(start + pageable.getPageSize(), results.size());
            List<ContentItem> pageContent = start < results.size() ? results.subList(start, end) : List.of();
            return new PageImpl<>(pageContent, pageable, results.size());
        }
        if (orgId != null) {
            return contentItemRepository.findWithFiltersAndOrg(orgId, type, state, pageable);
        }
        return contentItemRepository.findWithFilters(type, state, pageable);
    }

    @Transactional(readOnly = true)
    public ContentItem findById(UUID id, User actor) {
        UUID orgId = resolveOrgId(actor);
        if (orgId != null) {
            return contentItemRepository.findByIdAndOrganizationId(id, orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("ContentItem", id));
        }
        return contentItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ContentItem", id));
    }

    /** Internal lookup used by mutations that already call requireOrgAccess. */
    private ContentItem findByIdInternal(UUID id) {
        return contentItemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ContentItem", id));
    }

    @Transactional
    public ContentItem updateItem(UUID id, UpdateContentItemRequest request, User actor) {
        ContentItem item = findByIdInternal(id);
        requireOrgAccess(item, actor);

        requireVersion(request.getEntityVersion(), item.getVersion(), "ContentItem", id,
                Map.of("state", item.getState() != null ? item.getState().name() : "UNKNOWN",
                       "title", item.getTitle() != null ? item.getTitle() : ""));

        if (request.getTitle() != null) item.setTitle(request.getTitle());
        if (request.getSanitizedBody() != null) item.setSanitizedBody(htmlSanitizer.sanitize(request.getSanitizedBody()));
        if (request.getTags() != null) item.setTags(request.getTags());
        if (request.getScheduledAt() != null) {
            item.setScheduledAt(request.getScheduledAt());
            item.setState(ContentState.SCHEDULED);
        }
        return contentItemRepository.save(item);
    }

    @Transactional
    public ContentItem scheduleItem(UUID id, OffsetDateTime scheduledAt, User actor) {
        ContentItem item = findByIdInternal(id);
        requireOrgAccess(item, actor);
        if (item.getState() != ContentState.DRAFT) {
            throw new BusinessException("Only DRAFT items can be scheduled", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        item.setScheduledAt(scheduledAt);
        item.setState(ContentState.SCHEDULED);
        ContentItem saved = contentItemRepository.save(item);
        try {
            quartzSchedulerConfig.scheduleContentPublishJob(saved.getId(), scheduledAt);
        } catch (Exception e) {
            throw new BusinessException("Failed to schedule content publish job", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return saved;
    }

    @Transactional
    public ContentItem publishItem(UUID id, Integer entityVersion, User actor) {
        ContentItem item = findByIdInternal(id);
        requireOrgAccess(item, actor);

        requireVersion(entityVersion, item.getVersion(), "ContentItem", id,
                Map.of("state", item.getState() != null ? item.getState().name() : "UNKNOWN",
                       "title", item.getTitle() != null ? item.getTitle() : ""));

        if (item.getState() == ContentState.PUBLISHED) {
            throw new BusinessException("Item is already published", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        item.setState(ContentState.PUBLISHED);
        item.setPublishedAt(OffsetDateTime.now());
        ContentItem saved = contentItemRepository.save(item);
        saveSnapshot(saved, TransitionType.PUBLISHED);

        MDC.put("contentItemId", id.toString());
        log.info("PUBLISH_ITEM state=PUBLISHED title={}", saved.getTitle());
        MDC.remove("contentItemId");

        auditService.log(actor.getId(), "CONTENT_PUBLISHED", "content_items/" + id,
                Map.of("title", saved.getTitle()));

        if (saved.getCreatedBy() != null) {
            notificationService.createNotification(saved.getCreatedBy().getId(),
                    "CONTENT_PUBLISHED", "Content published: " + saved.getTitle(),
                    "Your content item \"" + saved.getTitle() + "\" has been published.",
                    "content_items/" + id);
        }

        return saved;
    }

    @Transactional
    public ContentItem unpublishItem(UUID id, Integer entityVersion, User actor) {
        ContentItem item = findByIdInternal(id);
        requireOrgAccess(item, actor);

        requireVersion(entityVersion, item.getVersion(), "ContentItem", id,
                Map.of("state", item.getState() != null ? item.getState().name() : "UNKNOWN",
                       "title", item.getTitle() != null ? item.getTitle() : ""));

        if (item.getState() != ContentState.PUBLISHED) {
            throw new BusinessException("Item is not currently published", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        item.setState(ContentState.UNPUBLISHED);
        ContentItem saved = contentItemRepository.save(item);
        saveSnapshot(saved, TransitionType.UNPUBLISHED);

        MDC.put("contentItemId", id.toString());
        log.info("UNPUBLISH_ITEM state=UNPUBLISHED title={}", saved.getTitle());
        MDC.remove("contentItemId");

        auditService.log(actor.getId(), "CONTENT_UNPUBLISHED", "content_items/" + id,
                Map.of("title", saved.getTitle()));

        if (saved.getCreatedBy() != null) {
            notificationService.createNotification(saved.getCreatedBy().getId(),
                    "CONTENT_UNPUBLISHED", "Content unpublished: " + saved.getTitle(),
                    "Your content item \"" + saved.getTitle() + "\" has been unpublished.",
                    "content_items/" + id);
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public List<ContentPublishSnapshot> getPublishHistory(UUID contentItemId, User actor) {
        // Verify the caller can access this content item first
        findById(contentItemId, actor);
        return snapshotRepository.findByContentItemIdOrderByCreatedAtDesc(contentItemId);
    }

    @Transactional(readOnly = true)
    public List<ContentItem> search(String query, String state, String type, User actor) {
        UUID orgId = resolveOrgId(actor);
        if (orgId != null) {
            return contentItemRepository.fullTextSearchByOrg(orgId, query, state, type);
        }
        return contentItemRepository.fullTextSearch(query, state, type);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static UUID resolveOrgId(User actor) {
        return AuthorizationService.resolveOrgId(actor);
    }

    private static void requireOrgAccess(ContentItem item, User actor) {
        UUID itemOrgId = item.getOrganization() != null ? item.getOrganization().getId() : null;
        AuthorizationService.checkOwnership(itemOrgId, actor, "ContentItem", item.getId());
    }

    private static void requireVersion(Integer requestedVersion, Integer serverVersion,
                                        String entityType, UUID entityId,
                                        Map<String, Object> stateSummary) {
        if (requestedVersion == null || !requestedVersion.equals(serverVersion)) {
            throw new VersionConflictException(entityType, entityId, serverVersion, stateSummary);
        }
    }

    private void saveSnapshot(ContentItem item, TransitionType transitionType) {
        ContentPublishSnapshot snap = new ContentPublishSnapshot();
        snap.setContentItemId(item.getId());
        snap.setTransitionType(transitionType);
        snap.setSnapshotTitle(item.getTitle());
        snap.setSnapshotBody(item.getSanitizedBody());
        snap.setSnapshotTags(item.getTags() != null ? String.join(",", item.getTags()) : null);
        snap.setSnapshotScheduledAt(item.getScheduledAt());
        snap.setSnapshotPublishedAt(item.getPublishedAt());
        snap.setSnapshotState(item.getState() != null ? item.getState().name() : null);
        snap.setSnapshotVersion(item.getVersion());
        snapshotRepository.save(snap);
    }
}
