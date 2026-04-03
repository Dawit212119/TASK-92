package com.civicworks.version;

import com.civicworks.domain.entity.ContentItem;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.ContentState;
import com.civicworks.dto.UpdateContentItemRequest;
import com.civicworks.exception.VersionConflictException;
import com.civicworks.repository.ContentItemRepository;
import com.civicworks.repository.ContentPublishSnapshotRepository;
import com.civicworks.scheduler.QuartzSchedulerConfig;
import com.civicworks.service.AuditService;
import com.civicworks.service.ContentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests proving optimistic-locking enforcement in ContentService.
 */
@ExtendWith(MockitoExtension.class)
class ContentServiceVersionTest {

    @Mock ContentItemRepository contentItemRepository;
    @Mock ContentPublishSnapshotRepository snapshotRepository;
    @Mock AuditService auditService;
    @Mock QuartzSchedulerConfig quartzSchedulerConfig;
    @Mock com.civicworks.service.HtmlSanitizerService htmlSanitizer;
    @Mock com.civicworks.service.NotificationService notificationService;

    private ContentService contentService;

    private final UUID itemId = UUID.randomUUID();
    private User actor;

    @BeforeEach
    void setUp() {
        contentService = new ContentService(
                contentItemRepository, snapshotRepository, auditService, quartzSchedulerConfig, htmlSanitizer,
                notificationService);
        actor = new User();
        actor.setId(UUID.randomUUID());
        actor.setUsername("editor");
        actor.setRole(com.civicworks.domain.enums.Role.SYSTEM_ADMIN);
    }

    // -----------------------------------------------------------------------
    // updateItem
    // -----------------------------------------------------------------------

    @Test
    void updateItem_correctVersion_succeeds() {
        ContentItem item = draftItem(4);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(contentItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateContentItemRequest req = updateRequest(4);
        req.setTitle("New Title");
        assertThatCode(() -> contentService.updateItem(itemId, req, actor)).doesNotThrowAnyException();
    }

    @Test
    void updateItem_staleVersion_throws409() {
        ContentItem item = draftItem(4);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        UpdateContentItemRequest req = updateRequest(2); // stale
        assertThatThrownBy(() -> contentService.updateItem(itemId, req, actor))
                .isInstanceOf(VersionConflictException.class)
                .satisfies(ex -> {
                    VersionConflictException vce = (VersionConflictException) ex;
                    assertThat(vce.getEntityType()).isEqualTo("ContentItem");
                    assertThat(vce.getServerVersion()).isEqualTo(4);
                    assertThat(vce.getStateSummary()).containsKey("state");
                    assertThat(vce.getStateSummary()).containsKey("title");
                });
    }

    // -----------------------------------------------------------------------
    // publishItem
    // -----------------------------------------------------------------------

    @Test
    void publishItem_correctVersion_succeeds() {
        ContentItem item = draftItem(1);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(contentItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> contentService.publishItem(itemId, 1, actor)).doesNotThrowAnyException();
    }

    @Test
    void publishItem_staleVersion_throws409() {
        ContentItem item = draftItem(1);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> contentService.publishItem(itemId, 0, actor))
                .isInstanceOf(VersionConflictException.class)
                .satisfies(ex -> assertThat(((VersionConflictException) ex).getServerVersion()).isEqualTo(1));
    }

    // -----------------------------------------------------------------------
    // unpublishItem
    // -----------------------------------------------------------------------

    @Test
    void unpublishItem_correctVersion_succeeds() {
        ContentItem item = publishedItem(7);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(contentItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(snapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatCode(() -> contentService.unpublishItem(itemId, 7, actor)).doesNotThrowAnyException();
    }

    @Test
    void unpublishItem_staleVersion_throws409() {
        ContentItem item = publishedItem(7);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> contentService.unpublishItem(itemId, 5, actor))
                .isInstanceOf(VersionConflictException.class)
                .satisfies(ex -> assertThat(((VersionConflictException) ex).getServerVersion()).isEqualTo(7));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ContentItem draftItem(int version) {
        ContentItem item = new ContentItem();
        item.setId(itemId);
        item.setTitle("Test Item");
        item.setState(ContentState.DRAFT);
        setVersion(item, version);
        return item;
    }

    private ContentItem publishedItem(int version) {
        ContentItem item = new ContentItem();
        item.setId(itemId);
        item.setTitle("Published Item");
        item.setState(ContentState.PUBLISHED);
        setVersion(item, version);
        return item;
    }

    private UpdateContentItemRequest updateRequest(int entityVersion) {
        UpdateContentItemRequest r = new UpdateContentItemRequest();
        r.setEntityVersion(entityVersion);
        return r;
    }

    private static void setVersion(Object entity, int version) {
        try {
            var field = entity.getClass().getDeclaredField("version");
            field.setAccessible(true);
            field.set(entity, version);
        } catch (Exception e) {
            throw new RuntimeException("Could not set version on " + entity.getClass().getSimpleName(), e);
        }
    }
}
