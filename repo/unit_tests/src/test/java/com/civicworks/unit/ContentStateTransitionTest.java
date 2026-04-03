package com.civicworks.unit;

import com.civicworks.domain.entity.ContentItem;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.ContentState;
import com.civicworks.exception.BusinessException;
import com.civicworks.exception.VersionConflictException;
import com.civicworks.repository.ContentItemRepository;
import com.civicworks.repository.ContentPublishSnapshotRepository;
import com.civicworks.scheduler.QuartzSchedulerConfig;
import com.civicworks.service.AuditService;
import com.civicworks.service.ContentService;
import com.civicworks.service.HtmlSanitizerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ContentService publish/unpublish state transitions.
 *
 * Verifies:
 *  - DRAFT → PUBLISHED succeeds
 *  - PUBLISHED → PUBLISHED throws 422
 *  - PUBLISHED → UNPUBLISHED succeeds
 *  - non-PUBLISHED → UNPUBLISHED throws 422
 *  - stale / null entityVersion throws VersionConflictException
 *  - publish creates exactly one snapshot
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentStateTransitionTest {

    @Mock ContentItemRepository contentItemRepository;
    @Mock ContentPublishSnapshotRepository snapshotRepository;
    @Mock AuditService auditService;
    @Mock QuartzSchedulerConfig quartzSchedulerConfig;
    @Mock com.civicworks.service.NotificationService notificationService;

    private ContentService contentService;
    private User actor;
    private final UUID itemId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        contentService = new ContentService(
                contentItemRepository, snapshotRepository,
                auditService, quartzSchedulerConfig, new HtmlSanitizerService(),
                notificationService);

        actor = new User();
        actor.setId(UUID.randomUUID());
        actor.setRole(com.civicworks.domain.enums.Role.SYSTEM_ADMIN);

        when(contentItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── publish ──────────────────────────────────────────────────────────────

    @Test
    void publishDraftItem_setsStatePublished() {
        ContentItem item = item(ContentState.DRAFT, 0);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        ContentItem result = contentService.publishItem(itemId, 0, actor);

        assertThat(result.getState()).isEqualTo(ContentState.PUBLISHED);
        assertThat(result.getPublishedAt()).isNotNull();
    }

    @Test
    void publishScheduledItem_setsStatePublished() {
        ContentItem item = item(ContentState.SCHEDULED, 1);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        ContentItem result = contentService.publishItem(itemId, 1, actor);

        assertThat(result.getState()).isEqualTo(ContentState.PUBLISHED);
    }

    @Test
    void publishUnpublishedItem_setsStatePublished() {
        ContentItem item = item(ContentState.UNPUBLISHED, 2);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        ContentItem result = contentService.publishItem(itemId, 2, actor);

        assertThat(result.getState()).isEqualTo(ContentState.PUBLISHED);
    }

    @Test
    void publishAlreadyPublishedItem_throwsBusinessException() {
        ContentItem item = item(ContentState.PUBLISHED, 3);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> contentService.publishItem(itemId, 3, actor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already published");
    }

    @Test
    void publish_createsOneSnapshot() {
        ContentItem item = item(ContentState.DRAFT, 0);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        contentService.publishItem(itemId, 0, actor);

        verify(snapshotRepository, times(1)).save(any());
    }

    @Test
    void publish_staleVersion_throwsVersionConflict() {
        ContentItem item = item(ContentState.DRAFT, 5);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> contentService.publishItem(itemId, 2, actor))
                .isInstanceOf(VersionConflictException.class)
                .satisfies(ex -> assertThat(((VersionConflictException) ex).getServerVersion()).isEqualTo(5));
    }

    @Test
    void publish_nullVersion_throwsVersionConflict() {
        ContentItem item = item(ContentState.DRAFT, 0);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> contentService.publishItem(itemId, null, actor))
                .isInstanceOf(VersionConflictException.class);
    }

    // ── unpublish ─────────────────────────────────────────────────────────────

    @Test
    void unpublishPublishedItem_setsStateUnpublished() {
        ContentItem item = item(ContentState.PUBLISHED, 4);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        ContentItem result = contentService.unpublishItem(itemId, 4, actor);

        assertThat(result.getState()).isEqualTo(ContentState.UNPUBLISHED);
    }

    @Test
    void unpublish_createsOneSnapshot() {
        ContentItem item = item(ContentState.PUBLISHED, 1);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        contentService.unpublishItem(itemId, 1, actor);

        verify(snapshotRepository, times(1)).save(any());
    }

    @Test
    void unpublishDraftItem_throwsBusinessException() {
        ContentItem item = item(ContentState.DRAFT, 0);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> contentService.unpublishItem(itemId, 0, actor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not currently published");
    }

    @Test
    void unpublishScheduledItem_throwsBusinessException() {
        ContentItem item = item(ContentState.SCHEDULED, 0);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> contentService.unpublishItem(itemId, 0, actor))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void unpublish_staleVersion_throwsVersionConflict() {
        ContentItem item = item(ContentState.PUBLISHED, 7);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> contentService.unpublishItem(itemId, 3, actor))
                .isInstanceOf(VersionConflictException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ContentItem item(ContentState state, int version) {
        ContentItem item = new ContentItem();
        item.setId(itemId);
        item.setState(state);
        item.setTitle("Test Title");
        setVersion(item, version);
        return item;
    }

    private static void setVersion(Object target, int version) {
        try {
            Field f = target.getClass().getDeclaredField("version");
            f.setAccessible(true);
            f.set(target, version);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
