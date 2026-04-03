package com.civicworks.unit;

import com.civicworks.domain.entity.Comment;
import com.civicworks.domain.entity.Organization;
import com.civicworks.domain.entity.SensitiveWord;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.ModerationState;
import com.civicworks.dto.CreateCommentRequest;
import com.civicworks.repository.CommentRepository;
import com.civicworks.repository.SensitiveWordRepository;
import com.civicworks.service.CommentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CommentService.createComment() — sensitive-word scoring:
 *
 *  0 hits  → ModerationState.APPROVED
 *  1 hit   → ModerationState.FLAGGED
 *  2+ hits → ModerationState.HOLD
 *
 * Also verifies:
 *  - case-insensitive matching
 *  - exact-word matching (word boundaries via \b — substrings do NOT count)
 *  - filterHitCount stored correctly
 *  - no sensitive words for org → always APPROVED
 *  - no org on user → no filtering
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentModerationTest {

    @Mock CommentRepository commentRepository;
    @Mock SensitiveWordRepository sensitiveWordRepository;
    @Mock com.civicworks.repository.ContentItemRepository contentItemRepository;

    private CommentService commentService;
    private final UUID orgId = UUID.randomUUID();
    private final UUID contentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        commentService = new CommentService(commentRepository, sensitiveWordRepository, contentItemRepository);
        when(commentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Stub content item lookup so org verification passes (actor has org → org-scoped lookup)
        com.civicworks.domain.entity.ContentItem stubItem = new com.civicworks.domain.entity.ContentItem();
        stubItem.setId(contentId);
        when(contentItemRepository.findByIdAndOrganizationId(eq(contentId), eq(orgId)))
                .thenReturn(java.util.Optional.of(stubItem));
        // For userWithoutOrg tests: resolveOrgId returns null → uses findById
        when(contentItemRepository.findById(contentId))
                .thenReturn(java.util.Optional.of(stubItem));
    }

    // ── 0 hits → APPROVED ────────────────────────────────────────────────────

    @Test
    void noSensitiveWords_commentIsApproved() {
        when(sensitiveWordRepository.findByOrganizationId(orgId)).thenReturn(List.of());

        Comment result = createComment("Totally clean text.", withOrg());

        assertThat(result.getModerationState()).isEqualTo(ModerationState.APPROVED);
        assertThat(result.getFilterHitCount()).isEqualTo(0);
    }

    @Test
    void sensitiveWordsExist_textClean_commentIsApproved() {
        when(sensitiveWordRepository.findByOrganizationId(orgId))
                .thenReturn(List.of(word("badword"), word("forbidden")));

        Comment result = createComment("This is totally fine.", withOrg());

        assertThat(result.getModerationState()).isEqualTo(ModerationState.APPROVED);
        assertThat(result.getFilterHitCount()).isEqualTo(0);
    }

    // ── 1 hit → FLAGGED ──────────────────────────────────────────────────────

    @Test
    void oneHit_commentIsFlagged() {
        when(sensitiveWordRepository.findByOrganizationId(orgId))
                .thenReturn(List.of(word("spam")));

        Comment result = createComment("This is spam content.", withOrg());

        assertThat(result.getModerationState()).isEqualTo(ModerationState.FLAGGED);
        assertThat(result.getFilterHitCount()).isEqualTo(1);
    }

    @Test
    void caseInsensitiveMatch_oneHit_flagged() {
        when(sensitiveWordRepository.findByOrganizationId(orgId))
                .thenReturn(List.of(word("BADWORD")));

        Comment result = createComment("Contains badword inside.", withOrg());

        assertThat(result.getModerationState()).isEqualTo(ModerationState.FLAGGED);
    }

    @Test
    void exactWordMatch_oneHit_flagged() {
        when(sensitiveWordRepository.findByOrganizationId(orgId))
                .thenReturn(List.of(word("bad")));

        // "bad" appears as a whole word — matched by word-boundary regex (\bbad\b)
        Comment result = createComment("This is bad content.", withOrg());
        assertThat(result.getModerationState()).isEqualTo(ModerationState.FLAGGED);
        assertThat(result.getFilterHitCount()).isEqualTo(1);
    }

    @Test
    void substringOnly_noHit_approved() {
        when(sensitiveWordRepository.findByOrganizationId(orgId))
                .thenReturn(List.of(word("bad")));

        // "bad" embedded inside another word — word-boundary matching must NOT flag it
        Comment result = createComment("This is badlybad.", withOrg());
        assertThat(result.getModerationState()).isEqualTo(ModerationState.APPROVED);
        assertThat(result.getFilterHitCount()).isEqualTo(0);
    }

    // ── 2 hits → HOLD ────────────────────────────────────────────────────────

    @Test
    void twoHits_commentIsOnHold() {
        when(sensitiveWordRepository.findByOrganizationId(orgId))
                .thenReturn(List.of(word("word1"), word("word2")));

        Comment result = createComment("Contains word1 and word2 here.", withOrg());

        assertThat(result.getModerationState()).isEqualTo(ModerationState.HOLD);
        assertThat(result.getFilterHitCount()).isEqualTo(2);
    }

    @Test
    void threeHits_commentIsOnHold() {
        when(sensitiveWordRepository.findByOrganizationId(orgId))
                .thenReturn(List.of(word("a"), word("b"), word("c")));

        Comment result = createComment("text a b c here", withOrg());

        assertThat(result.getModerationState()).isEqualTo(ModerationState.HOLD);
        assertThat(result.getFilterHitCount()).isEqualTo(3);
    }

    // ── no org → no filtering ─────────────────────────────────────────────────

    @Test
    void userWithoutOrg_isDeniedAccess() {
        User noOrgUser = new User();
        noOrgUser.setId(UUID.randomUUID());
        noOrgUser.setRole(com.civicworks.domain.enums.Role.CONTENT_EDITOR);
        // organization is null → non-admin users without org are denied

        assertThatThrownBy(() -> createComment("Any text at all.", noOrgUser))
                .isInstanceOf(com.civicworks.exception.BusinessException.class);
    }

    // ── persisted fields ──────────────────────────────────────────────────────

    @Test
    void commentFields_persistedCorrectly() {
        when(sensitiveWordRepository.findByOrganizationId(orgId)).thenReturn(List.of());
        User author = withOrg();

        Comment result = createComment("Hello world!", author);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(captor.capture());
        Comment saved = captor.getValue();
        assertThat(saved.getContentItemId()).isEqualTo(contentId);
        assertThat(saved.getAuthorId()).isEqualTo(author.getId());
        assertThat(saved.getContentText()).isEqualTo("Hello world!");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Comment createComment(String text, User author) {
        CreateCommentRequest req = new CreateCommentRequest();
        req.setContentText(text);
        return commentService.createComment(contentId, req, author);
    }

    private User withOrg() {
        User u = new User();
        u.setId(UUID.randomUUID());
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }

    private SensitiveWord word(String text) {
        SensitiveWord sw = new SensitiveWord();
        sw.setWord(text);
        return sw;
    }
}
