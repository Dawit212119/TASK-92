package com.civicworks.service;

import com.civicworks.domain.entity.Comment;
import com.civicworks.domain.entity.ContentItem;
import com.civicworks.domain.entity.SensitiveWord;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.ModerationState;
import com.civicworks.domain.enums.Role;
import com.civicworks.dto.CreateCommentRequest;
import com.civicworks.exception.ResourceNotFoundException;
import com.civicworks.repository.CommentRepository;
import com.civicworks.repository.ContentItemRepository;
import com.civicworks.repository.SensitiveWordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CommentService {

    private final CommentRepository commentRepository;
    private final SensitiveWordRepository sensitiveWordRepository;
    private final ContentItemRepository contentItemRepository;

    public CommentService(CommentRepository commentRepository,
                          SensitiveWordRepository sensitiveWordRepository,
                          ContentItemRepository contentItemRepository) {
        this.commentRepository = commentRepository;
        this.sensitiveWordRepository = sensitiveWordRepository;
        this.contentItemRepository = contentItemRepository;
    }

    @Transactional
    public Comment createComment(UUID contentItemId, CreateCommentRequest request, User author) {
        // Verify the content item is accessible to the author's org
        verifyContentItemAccess(contentItemId, author);

        // Load sensitive words scoped by organization
        UUID orgId = author.getOrganization() != null ? author.getOrganization().getId() : null;
        List<SensitiveWord> sensitiveWords = orgId != null
                ? sensitiveWordRepository.findByOrganizationId(orgId)
                : List.of();

        String text = request.getContentText();

        int hitCount = 0;
        for (SensitiveWord sw : sensitiveWords) {
            // Exact whole-word match (case-insensitive).
            // \b word boundaries prevent "cat" from matching "category".
            if (isExactWordMatch(text, sw.getWord())) {
                hitCount++;
            }
        }

        ModerationState state;
        if (hitCount >= 2) {
            state = ModerationState.HOLD;
        } else if (hitCount == 1) {
            state = ModerationState.FLAGGED;
        } else {
            state = ModerationState.APPROVED;
        }

        Comment comment = new Comment();
        comment.setContentItemId(contentItemId);
        comment.setParentId(request.getParentId());
        comment.setAuthorId(author.getId());
        comment.setContentText(text);
        comment.setFilterHitCount(hitCount);
        comment.setModerationState(state);

        return commentRepository.save(comment);
    }

    @Transactional(readOnly = true)
    public List<Comment> findByContentItemId(UUID contentItemId, User actor) {
        verifyContentItemAccess(contentItemId, actor);
        return commentRepository.findByContentItemId(contentItemId);
    }

    @Transactional(readOnly = true)
    public Comment findById(UUID id) {
        return commentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Comment", id));
    }

    // -------------------------------------------------------------------------

    private void verifyContentItemAccess(UUID contentItemId, User actor) {
        UUID actorOrgId = ContentService.resolveOrgId(actor);
        if (actorOrgId != null) {
            contentItemRepository.findByIdAndOrganizationId(contentItemId, actorOrgId)
                    .orElseThrow(() -> new ResourceNotFoundException("ContentItem", contentItemId));
        } else {
            contentItemRepository.findById(contentItemId)
                    .orElseThrow(() -> new ResourceNotFoundException("ContentItem", contentItemId));
        }
    }

    /**
     * Returns {@code true} when {@code word} appears as a complete token in
     * {@code text} (case-insensitive, Unicode word boundaries via {@code \b}).
     * Substrings do not count: "bad" matches "bad word" but NOT "badminton".
     */
    static boolean isExactWordMatch(String text, String word) {
        if (text == null || word == null || word.isBlank()) return false;
        Pattern pattern = Pattern.compile(
                "(?i)\\b" + Pattern.quote(word.trim()) + "\\b");
        return pattern.matcher(text).find();
    }
}
