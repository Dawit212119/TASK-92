package com.civicworks.unit;

import com.civicworks.domain.entity.ContentItem;
import com.civicworks.domain.entity.Organization;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.ContentState;
import com.civicworks.domain.enums.Role;
import com.civicworks.exception.ResourceNotFoundException;
import com.civicworks.repository.ContentItemRepository;
import com.civicworks.repository.ContentPublishSnapshotRepository;
import com.civicworks.scheduler.QuartzSchedulerConfig;
import com.civicworks.service.AuditService;
import com.civicworks.service.ContentService;
import com.civicworks.service.HtmlSanitizerService;
import com.civicworks.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies content tenant isolation:
 * - User from org A cannot read, update, publish, or comment on org B content
 * - SYSTEM_ADMIN can access any content
 * - findAll returns only org-scoped results for non-admin users
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossTenantContentTest {

    @Mock ContentItemRepository contentItemRepository;
    @Mock ContentPublishSnapshotRepository snapshotRepository;
    @Mock AuditService auditService;
    @Mock QuartzSchedulerConfig quartzSchedulerConfig;
    @Mock NotificationService notificationService;

    private ContentService contentService;

    private final UUID itemId = UUID.randomUUID();
    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        contentService = new ContentService(
                contentItemRepository, snapshotRepository,
                auditService, quartzSchedulerConfig, new HtmlSanitizerService(),
                notificationService);
    }

    // ── findById ──────────────────────────────────────────────────────────

    @Test
    void editorFromOrgA_cannotRead_itemInOrgB() {
        when(contentItemRepository.findByIdAndOrganizationId(itemId, orgA))
                .thenReturn(Optional.empty());

        User editorA = editorInOrg(orgA);

        assertThatThrownBy(() -> contentService.findById(itemId, editorA))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void editorFromOrgB_canRead_itemInOrgB() {
        ContentItem item = itemInOrg(orgB);
        when(contentItemRepository.findByIdAndOrganizationId(itemId, orgB))
                .thenReturn(Optional.of(item));

        User editorB = editorInOrg(orgB);

        assertThatCode(() -> contentService.findById(itemId, editorB))
                .doesNotThrowAnyException();
    }

    @Test
    void systemAdmin_canRead_anyItem() {
        ContentItem item = itemInOrg(orgB);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        User admin = adminInOrg(orgA);

        assertThatCode(() -> contentService.findById(itemId, admin))
                .doesNotThrowAnyException();
    }

    // ── publishItem ───────────────────────────────────────────────────────

    @Test
    void editorFromOrgA_cannotPublish_itemInOrgB() {
        ContentItem item = itemInOrg(orgB);
        item.setState(ContentState.DRAFT);
        when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

        User editorA = editorInOrg(orgA);

        assertThatThrownBy(() -> contentService.publishItem(itemId, 0, editorA))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── findAll ───────────────────────────────────────────────────────────

    @Test
    void findAll_nonAdmin_usesOrgScopedQuery() {
        when(contentItemRepository.findWithFiltersAndOrg(eq(orgA), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        User editorA = editorInOrg(orgA);
        contentService.findAll(null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 20), editorA);

        // Must use the org-scoped query, NOT the unscoped one
        verify(contentItemRepository).findWithFiltersAndOrg(eq(orgA), isNull(), isNull(), any(Pageable.class));
        verify(contentItemRepository, never()).findWithFilters(any(), any(), any(Pageable.class));
    }

    @Test
    void findAll_admin_usesGlobalQuery() {
        when(contentItemRepository.findWithFilters(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        User admin = adminInOrg(orgA);
        contentService.findAll(null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 20), admin);

        verify(contentItemRepository).findWithFilters(isNull(), isNull(), any(Pageable.class));
        verify(contentItemRepository, never()).findWithFiltersAndOrg(any(), any(), any(), any(Pageable.class));
    }

    // ── search ────────────────────────────────────────────────────────────

    @Test
    void search_nonAdmin_usesOrgScopedFullText() {
        when(contentItemRepository.fullTextSearchByOrg(eq(orgA), eq("test"), isNull(), isNull()))
                .thenReturn(List.of());

        User editorA = editorInOrg(orgA);
        contentService.search("test", null, null, editorA);

        verify(contentItemRepository).fullTextSearchByOrg(eq(orgA), eq("test"), isNull(), isNull());
        verify(contentItemRepository, never()).fullTextSearch(any(), any(), any());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private ContentItem itemInOrg(UUID orgId) {
        ContentItem item = new ContentItem();
        item.setId(itemId);
        item.setTitle("Test Item");
        item.setState(ContentState.DRAFT);
        Organization org = new Organization();
        org.setId(orgId);
        item.setOrganization(org);
        return item;
    }

    private User editorInOrg(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.CONTENT_EDITOR);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }

    private User adminInOrg(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.SYSTEM_ADMIN);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }
}
