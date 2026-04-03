package com.civicworks.controller;

import com.civicworks.config.AuthUtils;
import com.civicworks.config.IdempotencyGuard;
import com.civicworks.domain.entity.Comment;
import com.civicworks.domain.entity.ContentItem;
import com.civicworks.domain.entity.ContentPublishSnapshot;
import com.civicworks.domain.entity.User;
import com.civicworks.dto.CreateCommentRequest;
import com.civicworks.dto.CreateContentItemRequest;
import com.civicworks.dto.UpdateContentItemRequest;
import com.civicworks.service.CommentService;
import com.civicworks.service.ContentService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ContentController {

    private final ContentService contentService;
    private final CommentService commentService;
    private final AuthUtils authUtils;
    private final IdempotencyGuard idempotencyGuard;

    public ContentController(ContentService contentService,
                             CommentService commentService,
                             AuthUtils authUtils,
                             IdempotencyGuard idempotencyGuard) {
        this.contentService = contentService;
        this.commentService = commentService;
        this.authUtils = authUtils;
        this.idempotencyGuard = idempotencyGuard;
    }

    @PostMapping("/content/items")
    @PreAuthorize("hasRole('CONTENT_EDITOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ContentItem> createItem(
            @Valid @RequestBody CreateContentItemRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return idempotencyGuard.execute(idempotencyKey, actor.getId(), "CREATE_CONTENT_ITEM",
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(contentService.createItem(request, actor)));
    }

    @GetMapping("/content/items")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> findAll(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);

        org.springframework.data.domain.Pageable pageable = PageRequest.of(
                Math.max(0, page), Math.min(100, size));

        Page<ContentItem> result = contentService.findAll(type, state, q, pageable, actor);

        return ResponseEntity.ok(Map.of(
                "data", result.getContent(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "total", result.getTotalElements()
        ));
    }

    @GetMapping("/content/items/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ContentItem> findById(@PathVariable UUID id,
                                                  Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return ResponseEntity.ok(contentService.findById(id, actor));
    }

    @PatchMapping("/content/items/{id}")
    @PreAuthorize("hasRole('CONTENT_EDITOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ContentItem> updateItem(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateContentItemRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return idempotencyGuard.execute(idempotencyKey, actor.getId(), "UPDATE_CONTENT_ITEM",
                () -> ResponseEntity.ok(contentService.updateItem(id, request, actor)));
    }

    @PostMapping("/content/items/{id}/schedule")
    @PreAuthorize("hasRole('CONTENT_EDITOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ContentItem> scheduleItem(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime scheduledAt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return idempotencyGuard.execute(idempotencyKey, actor.getId(), "SCHEDULE_CONTENT_ITEM",
                () -> ResponseEntity.ok(contentService.scheduleItem(id, scheduledAt, actor)));
    }

    @PostMapping("/content/items/{id}/publish")
    @PreAuthorize("hasRole('CONTENT_EDITOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ContentItem> publishItem(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Entity-Version") Integer entityVersion,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        MDC.put("contentItemId", id.toString());
        try {
            return idempotencyGuard.execute(idempotencyKey, actor.getId(), "PUBLISH_CONTENT",
                    () -> ResponseEntity.ok(contentService.publishItem(id, entityVersion, actor)));
        } finally {
            MDC.remove("contentItemId");
        }
    }

    @PostMapping("/content/items/{id}/unpublish")
    @PreAuthorize("hasRole('CONTENT_EDITOR') or hasRole('SYSTEM_ADMIN') or hasRole('MODERATOR')")
    public ResponseEntity<ContentItem> unpublishItem(
            @PathVariable UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Entity-Version") Integer entityVersion,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        MDC.put("contentItemId", id.toString());
        try {
            return idempotencyGuard.execute(idempotencyKey, actor.getId(), "UNPUBLISH_CONTENT",
                    () -> ResponseEntity.ok(contentService.unpublishItem(id, entityVersion, actor)));
        } finally {
            MDC.remove("contentItemId");
        }
    }

    @GetMapping("/content/items/{id}/publish-history")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<ContentPublishSnapshot>> getPublishHistory(@PathVariable UUID id,
                                                                            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return ResponseEntity.ok(contentService.getPublishHistory(id, actor));
    }

    @PostMapping("/content/items/{contentId}/comments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Comment> createComment(
            @PathVariable UUID contentId,
            @Valid @RequestBody CreateCommentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return idempotencyGuard.execute(idempotencyKey, actor.getId(), "CREATE_COMMENT",
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(commentService.createComment(contentId, request, actor)));
    }

    @GetMapping("/content/items/{contentId}/comments")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<Comment>> getComments(@PathVariable UUID contentId,
                                                       Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return ResponseEntity.ok(commentService.findByContentItemId(contentId, actor));
    }
}
