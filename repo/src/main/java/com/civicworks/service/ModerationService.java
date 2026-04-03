package com.civicworks.service;

import com.civicworks.domain.entity.Comment;
import com.civicworks.domain.entity.SensitiveWord;
import com.civicworks.domain.entity.User;
import com.civicworks.dto.CreateSensitiveWordRequest;
import com.civicworks.dto.ModerateCommentRequest;
import com.civicworks.exception.BusinessException;
import com.civicworks.exception.ResourceNotFoundException;
import com.civicworks.exception.VersionConflictException;
import com.civicworks.repository.CommentRepository;
import com.civicworks.repository.ContentItemRepository;
import com.civicworks.repository.SensitiveWordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ModerationService {

    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);

    private final SensitiveWordRepository sensitiveWordRepository;
    private final CommentRepository commentRepository;
    private final ContentItemRepository contentItemRepository;
    private final AuditService auditService;

    public ModerationService(SensitiveWordRepository sensitiveWordRepository,
                              CommentRepository commentRepository,
                              ContentItemRepository contentItemRepository,
                              AuditService auditService) {
        this.sensitiveWordRepository = sensitiveWordRepository;
        this.commentRepository = commentRepository;
        this.contentItemRepository = contentItemRepository;
        this.auditService = auditService;
    }

    @Transactional
    public SensitiveWord addWord(CreateSensitiveWordRequest request, User actor) {
        UUID orgId = actor.getOrganization() != null ? actor.getOrganization().getId() : null;
        if (sensitiveWordRepository.existsByOrganizationIdAndWord(orgId, request.getWord())) {
            throw new BusinessException("Sensitive word already exists for this organization",
                    HttpStatus.CONFLICT, "DUPLICATE_SENSITIVE_WORD");
        }
        SensitiveWord word = new SensitiveWord();
        word.setOrganizationId(orgId);
        word.setWord(request.getWord());
        word.setReplacement(request.getReplacement());
        return sensitiveWordRepository.save(word);
    }

    @Transactional(readOnly = true)
    public List<SensitiveWord> listWords(UUID organizationId) {
        return sensitiveWordRepository.findByOrganizationId(organizationId);
    }

    @Transactional(readOnly = true)
    public Page<SensitiveWord> listWordsPage(UUID organizationId, Pageable pageable) {
        return sensitiveWordRepository.findByOrganizationId(organizationId, pageable);
    }

    @Transactional
    public Comment moderateComment(UUID commentId, ModerateCommentRequest request, User actor) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));

        // Org check: verify the comment's parent content item belongs to actor's org
        UUID actorOrgId = AuthorizationService.resolveOrgId(actor);
        if (actorOrgId != null) {
            contentItemRepository.findByIdAndOrganizationId(comment.getContentItemId(), actorOrgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Comment", commentId));
        }

        // If the caller supplied an entityVersion, enforce it to prevent
        // two moderators from unknowingly overwriting each other's decision.
        if (request.getEntityVersion() != null
                && !request.getEntityVersion().equals(comment.getVersion())) {
            throw new VersionConflictException("Comment", commentId, comment.getVersion(),
                    Map.of("moderationState", comment.getModerationState().name()));
        }

        comment.setModerationState(request.getModerationState());
        Comment saved = commentRepository.save(comment);

        MDC.put("commentId", commentId.toString());
        log.info("COMMENT_MODERATED state={} actorId={}", request.getModerationState(), actor.getId());
        MDC.remove("commentId");

        auditService.log(actor.getId(), "COMMENT_MODERATED", "comments/" + commentId,
                Map.of("newState", request.getModerationState().toString(),
                       "reason", request.getReason() != null ? request.getReason() : ""));

        return saved;
    }
}
