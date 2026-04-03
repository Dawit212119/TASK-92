package com.civicworks.unit;

import com.civicworks.config.AuthUtils;
import com.civicworks.controller.KpiReportController;
import com.civicworks.domain.entity.KpiReport;
import com.civicworks.domain.entity.Organization;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.Role;
import com.civicworks.exception.BusinessException;
import com.civicworks.repository.KpiReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies KPI report tenant isolation:
 * - SYSTEM_ADMIN gets a list with reports from multiple orgs.
 * - AUDITOR from org A sees only org A's KPI, nothing from other orgs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossTenantKpiTest {

    @Mock KpiReportRepository kpiReportRepository;
    @Mock AuthUtils authUtils;
    @Mock Authentication authentication;

    private KpiReportController controller;

    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new KpiReportController(kpiReportRepository, authUtils);
    }

    @Test
    void systemAdmin_getsListWithMultipleOrgs() {
        User admin = userWithRole(Role.SYSTEM_ADMIN, orgA);
        when(authUtils.resolveUser(authentication)).thenReturn(admin);

        KpiReport reportA = kpiForOrg(orgA);
        KpiReport reportB = kpiForOrg(orgB);
        when(kpiReportRepository.findLatestPerOrganization()).thenReturn(List.of(reportA, reportB));

        ResponseEntity<List<KpiReport>> response = controller.getLatestKpiReports(authentication);

        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody()).extracting(KpiReport::getOrganizationId)
                .containsExactlyInAnyOrder(orgA, orgB);

        verify(kpiReportRepository).findLatestPerOrganization();
        verify(kpiReportRepository, never()).findLatestForOrganization(any());
    }

    @Test
    void auditorFromOrgA_seesOnlyOrgAKpi() {
        User auditorA = userWithRole(Role.AUDITOR, orgA);
        when(authUtils.resolveUser(authentication)).thenReturn(auditorA);

        KpiReport reportA = kpiForOrg(orgA);
        when(kpiReportRepository.findLatestForOrganization(orgA)).thenReturn(Optional.of(reportA));

        ResponseEntity<List<KpiReport>> response = controller.getLatestKpiReports(authentication);

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getOrganizationId()).isEqualTo(orgA);

        verify(kpiReportRepository).findLatestForOrganization(orgA);
        verify(kpiReportRepository, never()).findLatestPerOrganization();
    }

    @Test
    void auditorFromOrgA_doesNotSeeOrgBKpi() {
        User auditorA = userWithRole(Role.AUDITOR, orgA);
        when(authUtils.resolveUser(authentication)).thenReturn(auditorA);
        when(kpiReportRepository.findLatestForOrganization(orgA)).thenReturn(Optional.empty());

        ResponseEntity<List<KpiReport>> response = controller.getLatestKpiReports(authentication);

        assertThat(response.getBody()).isEmpty();
        verify(kpiReportRepository, never()).findLatestPerOrganization();
    }

    @Test
    void auditorWithNoOrg_isDeniedAccess() {
        User auditorNoOrg = new User();
        auditorNoOrg.setId(UUID.randomUUID());
        auditorNoOrg.setRole(Role.AUDITOR);
        // no organization set → resolveOrgId fails closed with 403
        when(authUtils.resolveUser(authentication)).thenReturn(auditorNoOrg);

        assertThatThrownBy(() -> controller.getLatestKpiReports(authentication))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException bex = (BusinessException) ex;
                    assertThat(bex.getCode()).isEqualTo("MISSING_ORGANIZATION");
                    assertThat(bex.getStatus().value()).isEqualTo(403);
                });

        verify(kpiReportRepository, never()).findLatestPerOrganization();
        verify(kpiReportRepository, never()).findLatestForOrganization(any());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private KpiReport kpiForOrg(UUID orgId) {
        KpiReport r = new KpiReport();
        r.setId(UUID.randomUUID());
        r.setOrganizationId(orgId);
        r.setWeekStart(LocalDate.of(2026, 3, 30));
        r.setTotalArrearsCents(50_000L);
        r.setGeneratedAt(OffsetDateTime.now());
        return r;
    }

    private User userWithRole(Role role, UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }
}
