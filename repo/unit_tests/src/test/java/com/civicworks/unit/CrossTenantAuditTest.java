package com.civicworks.unit;

import com.civicworks.domain.entity.AuditLog;
import com.civicworks.domain.entity.Organization;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.Role;
import com.civicworks.repository.AuditLogRepository;
import com.civicworks.repository.UserRepository;
import com.civicworks.service.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies audit-log tenant isolation:
 * - AUDITOR in org A cannot see logs from org B.
 * - SYSTEM_ADMIN can see logs from all orgs.
 * - log() sets organizationId based on the actor's org.
 * - Pagination and filter parameters are preserved.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossTenantAuditTest {

    @Mock AuditLogRepository auditLogRepository;
    @Mock UserRepository userRepository;

    private AuditService auditService;

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        auditService = new AuditService(auditLogRepository, userRepository, new ObjectMapper());
    }

    // ── findAll: org-scoped ──────────────────────────────────────────────

    @Test
    void auditorInOrgA_seesOnlyOrgALogs() {
        when(auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(eq(orgA), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(logInOrg(orgA))));

        User auditorA = auditorInOrg(orgA);
        Pageable pageable = PageRequest.of(0, 50);
        var page = auditService.findAll(auditorA, pageable);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getOrganizationId()).isEqualTo(orgA);

        verify(auditLogRepository).findAllByOrganizationIdOrderByCreatedAtDesc(eq(orgA), any(Pageable.class));
        verify(auditLogRepository, never()).findAllByOrderByCreatedAtDesc(any(Pageable.class));
    }

    @Test
    void auditorInOrgA_cannotSeeOrgBLogs() {
        when(auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(eq(orgA), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        User auditorA = auditorInOrg(orgA);
        var page = auditService.findAll(auditorA, PageRequest.of(0, 50));

        assertThat(page.getContent()).isEmpty();
        verify(auditLogRepository).findAllByOrganizationIdOrderByCreatedAtDesc(eq(orgA), any(Pageable.class));
    }

    // ── findAll: SYSTEM_ADMIN global ─────────────────────────────────────

    @Test
    void systemAdmin_seesAllLogs() {
        AuditLog logA = logInOrg(orgA);
        AuditLog logB = logInOrg(orgB);
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(logA, logB)));

        User admin = adminInOrg(orgA);
        var page = auditService.findAll(admin, PageRequest.of(0, 50));

        assertThat(page.getContent()).hasSize(2);
        verify(auditLogRepository).findAllByOrderByCreatedAtDesc(any(Pageable.class));
        verify(auditLogRepository, never()).findAllByOrganizationIdOrderByCreatedAtDesc(any(), any(Pageable.class));
    }

    // ── findWithFilters: org-scoped ──────────────────────────────────────

    @Test
    void auditor_findWithFilters_passesOrgId() {
        when(auditLogRepository.findWithFilters(eq(orgA), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        User auditorA = auditorInOrg(orgA);
        auditService.findWithFilters(auditorA, "bills/123", "PAYMENT_POSTED", null, null, PageRequest.of(0, 50));

        verify(auditLogRepository).findWithFilters(eq(orgA), eq("bills/123"), eq("PAYMENT_POSTED"),
                isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void systemAdmin_findWithFilters_passesNullOrgId() {
        when(auditLogRepository.findWithFilters(isNull(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        User admin = adminInOrg(orgA);
        auditService.findWithFilters(admin, null, "PAYMENT_POSTED", null, null, PageRequest.of(0, 50));

        verify(auditLogRepository).findWithFilters(isNull(), isNull(), eq("PAYMENT_POSTED"),
                isNull(), isNull(), any(Pageable.class));
    }

    // ── log(): sets organizationId ───────────────────────────────────────

    @Test
    void log_setsOrganizationId_fromActorOrg() {
        UUID actorId = UUID.randomUUID();
        User actor = auditorInOrg(orgA);
        actor.setId(actorId);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));

        auditService.log(actorId, "TEST_ACTION", "test/1", Map.of("key", "value"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(orgA);
    }

    @Test
    void log_setsNullOrganizationId_whenActorIsNull() {
        auditService.log(null, "SYSTEM_ACTION", "system/1", Map.of());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId()).isNull();
    }

    @Test
    void log_setsNullOrganizationId_whenActorHasNoOrg() {
        UUID actorId = UUID.randomUUID();
        User actor = new User();
        actor.setId(actorId);
        actor.setRole(Role.SYSTEM_ADMIN);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));

        auditService.log(actorId, "ADMIN_ACTION", "admin/1", "{\"info\":true}");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId()).isNull();
    }

    // ── pagination preserved ─────────────────────────────────────────────

    @Test
    void findAll_preservesPagination() {
        Pageable pageable = PageRequest.of(2, 25);
        when(auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(eq(orgA), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 100));

        User auditor = auditorInOrg(orgA);
        var page = auditService.findAll(auditor, pageable);

        assertThat(page.getNumber()).isEqualTo(2);
        assertThat(page.getSize()).isEqualTo(25);
        assertThat(page.getTotalElements()).isEqualTo(100);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private AuditLog logInOrg(UUID orgId) {
        AuditLog entry = new AuditLog();
        entry.setId(UUID.randomUUID());
        entry.setOrganizationId(orgId);
        entry.setAction("TEST");
        return entry;
    }

    private User auditorInOrg(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.AUDITOR);
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
