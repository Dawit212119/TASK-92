package com.civicworks.unit;

import com.civicworks.config.AuthUtils;
import com.civicworks.domain.entity.Organization;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.Role;
import com.civicworks.repository.*;
import com.civicworks.scheduler.QuartzSchedulerConfig;
import com.civicworks.service.AuditService;
import com.civicworks.service.BillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that SYSTEM_ADMIN always passes {@code orgId = null} to the billing
 * repository (global view), while BILLING_CLERK is scoped to their own org.
 *
 * <p>Tests the controller logic directly rather than standing up a full
 * Spring context — the controller's role-to-orgId mapping is extracted and
 * exercised in isolation via the resolved {@link User} and {@link BillingService}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingAdminScopeTest {

    @Mock FeeItemRepository feeItemRepository;
    @Mock BillingRunRepository billingRunRepository;
    @Mock BillRepository billRepository;
    @Mock BillDiscountRepository billDiscountRepository;
    @Mock LateFeeEventRepository lateFeeEventRepository;
    @Mock AccountRepository accountRepository;
    @Mock UsageRecordRepository usageRecordRepository;
    @Mock AuditService auditService;
    @Mock QuartzSchedulerConfig quartzSchedulerConfig;
    @Mock com.civicworks.service.NotificationService notificationService;
    @Mock UserRepository userRepository;

    private BillingService billingService;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        billingService = new BillingService(
                feeItemRepository, billingRunRepository, billRepository,
                billDiscountRepository, lateFeeEventRepository, accountRepository,
                usageRecordRepository, auditService, quartzSchedulerConfig,
                notificationService, userRepository);

        when(billRepository.findWithFiltersAndOrg(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
    }

    /**
     * Mirrors the controller logic:
     * {@code actor.getRole() == Role.SYSTEM_ADMIN ? null : actor.getOrganization().getId()}
     */
    private static UUID resolveOrgId(User actor) {
        return actor.getRole() == Role.SYSTEM_ADMIN ? null
                : (actor.getOrganization() != null ? actor.getOrganization().getId() : null);
    }

    @Test
    void systemAdmin_withOrg_orgIdIsNull_globalView() {
        // SYSTEM_ADMIN has an org assigned but must still see all bills.
        User admin = new User();
        admin.setRole(Role.SYSTEM_ADMIN);
        Organization org = new Organization();
        org.setId(orgId);
        admin.setOrganization(org);

        UUID resolved = resolveOrgId(admin);
        assertThat(resolved).isNull();

        billingService.findBillsPage(resolved, null, null, 0, 20);

        ArgumentCaptor<UUID> orgCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(billRepository).findWithFiltersAndOrg(orgCaptor.capture(), isNull(), isNull(), any(Pageable.class));
        assertThat(orgCaptor.getValue()).isNull();
    }

    @Test
    void systemAdmin_withoutOrg_orgIdIsNull_globalView() {
        User admin = new User();
        admin.setRole(Role.SYSTEM_ADMIN);
        // no org set

        UUID resolved = resolveOrgId(admin);
        assertThat(resolved).isNull();
    }

    @Test
    void billingClerk_withOrg_orgIdIsOrgId_scopedView() {
        User clerk = new User();
        clerk.setRole(Role.BILLING_CLERK);
        Organization org = new Organization();
        org.setId(orgId);
        clerk.setOrganization(org);

        UUID resolved = resolveOrgId(clerk);
        assertThat(resolved).isEqualTo(orgId);

        billingService.findBillsPage(resolved, null, null, 0, 20);

        ArgumentCaptor<UUID> orgCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(billRepository).findWithFiltersAndOrg(orgCaptor.capture(), isNull(), isNull(), any(Pageable.class));
        assertThat(orgCaptor.getValue()).isEqualTo(orgId);
    }

    @Test
    void auditor_withOrg_orgIdIsOrgId_scopedView() {
        User auditor = new User();
        auditor.setRole(Role.AUDITOR);
        Organization org = new Organization();
        org.setId(orgId);
        auditor.setOrganization(org);

        UUID resolved = resolveOrgId(auditor);
        assertThat(resolved).isEqualTo(orgId);
    }
}
