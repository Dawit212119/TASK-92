package com.civicworks.unit;

import com.civicworks.domain.entity.*;
import com.civicworks.domain.enums.*;
import com.civicworks.dto.CreateBillingRunRequest;
import com.civicworks.dto.CreateDispatchOrderRequest;
import com.civicworks.exception.BusinessException;
import com.civicworks.repository.*;
import com.civicworks.scheduler.QuartzSchedulerConfig;
import com.civicworks.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Regression tests for three security gaps:
 *  1) DRIVER cannot create forced-dispatch orders (forcedFlag=true + assignedDriverId).
 *  2) BILLING_CLERK billing runs are org-scoped; only the clerk's org accounts are billed.
 *  3) Non-admin users without org assignment are denied billing reads (fail closed).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecurityRegressionTest {

    // ═══════════════════════════════════════════════════════════════════════
    //  1) Forced dispatch role guard
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ForcedDispatchRoleGuard {

        @Mock DispatchOrderRepository dispatchOrderRepository;
        @Mock ZoneRepository zoneRepository;
        @Mock ZoneQueueRepository zoneQueueRepository;
        @Mock DriverOnlineSessionRepository driverOnlineSessionRepository;
        @Mock DriverCooldownRepository driverCooldownRepository;
        @Mock UserRepository userRepository;
        @Mock AuditService auditService;
        @Mock NotificationService notificationService;

        private DispatchService dispatchService;

        @BeforeEach
        void setUp() {
            dispatchService = new DispatchService(
                    dispatchOrderRepository, zoneRepository, zoneQueueRepository,
                    driverOnlineSessionRepository, driverCooldownRepository,
                    userRepository, auditService, notificationService);
        }

        @Test
        void driver_cannotCreateForcedDispatch() {
            User driver = userInOrg(Role.DRIVER);
            CreateDispatchOrderRequest req = forcedDispatchRequest();

            assertThatThrownBy(() -> dispatchService.createOrder(req, driver))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException bex = (BusinessException) ex;
                        assertThat(bex.getCode()).isEqualTo("FORCED_DISPATCH_FORBIDDEN");
                        assertThat(bex.getStatus().value()).isEqualTo(403);
                    });

            verify(dispatchOrderRepository, never()).save(any());
        }

        @Test
        void billingClerk_cannotCreateForcedDispatch() {
            User clerk = userInOrg(Role.BILLING_CLERK);
            CreateDispatchOrderRequest req = forcedDispatchRequest();

            assertThatThrownBy(() -> dispatchService.createOrder(req, clerk))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex ->
                            assertThat(((BusinessException) ex).getCode()).isEqualTo("FORCED_DISPATCH_FORBIDDEN"));
        }

        @Test
        void dispatcher_canCreateForcedDispatch() {
            User dispatcher = userInOrg(Role.DISPATCHER);
            CreateDispatchOrderRequest req = forcedDispatchRequest();

            Zone zone = new Zone();
            zone.setId(req.getZoneId());
            zone.setMaxConcurrentOrders(100);
            when(zoneRepository.findById(req.getZoneId())).thenReturn(Optional.of(zone));
            when(dispatchOrderRepository.countActiveOrdersInZone(any(), any())).thenReturn(0L);
            when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatCode(() -> dispatchService.createOrder(req, dispatcher))
                    .doesNotThrowAnyException();
        }

        @Test
        void systemAdmin_canCreateForcedDispatch() {
            User admin = new User();
            admin.setId(UUID.randomUUID());
            admin.setRole(Role.SYSTEM_ADMIN);
            CreateDispatchOrderRequest req = forcedDispatchRequest();

            Zone zone = new Zone();
            zone.setId(req.getZoneId());
            zone.setMaxConcurrentOrders(100);
            when(zoneRepository.findById(req.getZoneId())).thenReturn(Optional.of(zone));
            when(dispatchOrderRepository.countActiveOrdersInZone(any(), any())).thenReturn(0L);
            when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatCode(() -> dispatchService.createOrder(req, admin))
                    .doesNotThrowAnyException();
        }

        private CreateDispatchOrderRequest forcedDispatchRequest() {
            CreateDispatchOrderRequest r = new CreateDispatchOrderRequest();
            r.setZoneId(UUID.randomUUID());
            r.setMode(OrderMode.DISPATCHER_ASSIGNED);
            r.setPickupLat(BigDecimal.valueOf(37.77));
            r.setPickupLng(BigDecimal.valueOf(-122.41));
            r.setForcedFlag(true);
            r.setAssignedDriverId(UUID.randomUUID());
            return r;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  2) Billing runs org-scoped
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class BillingRunOrgScoping {

        @Mock FeeItemRepository feeItemRepository;
        @Mock BillingRunRepository billingRunRepository;
        @Mock BillRepository billRepository;
        @Mock BillDiscountRepository billDiscountRepository;
        @Mock LateFeeEventRepository lateFeeEventRepository;
        @Mock AccountRepository accountRepository;
        @Mock UsageRecordRepository usageRecordRepository;
        @Mock AuditService auditService;
        @Mock QuartzSchedulerConfig quartzSchedulerConfig;
        @Mock NotificationService notificationService;
        @Mock UserRepository userRepository;

        private BillingService billingService;
        private final UUID orgA = UUID.randomUUID();
        private final UUID orgB = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            billingService = new BillingService(
                    feeItemRepository, billingRunRepository, billRepository,
                    billDiscountRepository, lateFeeEventRepository, accountRepository,
                    usageRecordRepository, auditService, quartzSchedulerConfig,
                    notificationService, userRepository);
        }

        @Test
        void billingRun_storesActorOrgId() {
            User clerk = userInOrg(Role.BILLING_CLERK, orgA);

            CreateBillingRunRequest req = new CreateBillingRunRequest();
            req.setCycleDate(LocalDate.of(2026, 5, 1));
            req.setBillingCycle(BillingCycle.MONTHLY);
            req.setIdempotencyKey("test-key-1");

            when(billingRunRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
            when(billingRunRepository.save(any(BillingRun.class))).thenAnswer(inv -> inv.getArgument(0));

            BillingRun result = billingService.createBillingRun(req, clerk);

            assertThat(result.getOrganizationId()).isEqualTo(orgA);
        }

        @Test
        void executeBillingRun_queriesOnlyOrgAccounts() {
            BillingRun run = new BillingRun();
            run.setId(UUID.randomUUID());
            run.setStatus(BillingRunStatus.PENDING);
            run.setCycleDate(LocalDate.of(2026, 5, 1));
            run.setOrganizationId(orgA);

            when(billingRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
            when(billingRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(billRepository.findByBillingRunId(run.getId())).thenReturn(List.of());
            when(accountRepository.findByOrganizationId(orgA)).thenReturn(List.of());

            billingService.executeBillingRun(run.getId());

            // Must query by org, never findAll()
            verify(accountRepository).findByOrganizationId(orgA);
            verify(accountRepository, never()).findAll();
        }

        @Test
        void executeBillingRun_adminGlobalRun_queriesAllAccounts() {
            BillingRun run = new BillingRun();
            run.setId(UUID.randomUUID());
            run.setStatus(BillingRunStatus.PENDING);
            run.setCycleDate(LocalDate.of(2026, 5, 1));
            run.setOrganizationId(null); // SYSTEM_ADMIN created → global

            when(billingRunRepository.findById(run.getId())).thenReturn(Optional.of(run));
            when(billingRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(billRepository.findByBillingRunId(run.getId())).thenReturn(List.of());
            when(accountRepository.findAll()).thenReturn(List.of());

            billingService.executeBillingRun(run.getId());

            verify(accountRepository).findAll();
            verify(accountRepository, never()).findByOrganizationId(any());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  3) Non-admin without org denied billing reads (fail closed)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class BillingReadFailClosed {

        @Test
        void nonAdminWithoutOrg_resolveOrgId_throws403() {
            User auditor = new User();
            auditor.setId(UUID.randomUUID());
            auditor.setRole(Role.AUDITOR);
            // no organization

            assertThatThrownBy(() -> AuthorizationService.resolveOrgId(auditor))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException bex = (BusinessException) ex;
                        assertThat(bex.getCode()).isEqualTo("MISSING_ORGANIZATION");
                        assertThat(bex.getStatus().value()).isEqualTo(403);
                    });
        }

        @Test
        void billingClerkWithoutOrg_resolveOrgId_throws403() {
            User clerk = new User();
            clerk.setId(UUID.randomUUID());
            clerk.setRole(Role.BILLING_CLERK);

            assertThatThrownBy(() -> AuthorizationService.resolveOrgId(clerk))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex ->
                            assertThat(((BusinessException) ex).getCode()).isEqualTo("MISSING_ORGANIZATION"));
        }

        @Test
        void systemAdminWithoutOrg_getsGlobalAccess() {
            User admin = new User();
            admin.setId(UUID.randomUUID());
            admin.setRole(Role.SYSTEM_ADMIN);

            assertThat(AuthorizationService.resolveOrgId(admin)).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Shared helpers
    // ═══════════════════════════════════════════════════════════════════════

    static User userInOrg(Role role) {
        return userInOrg(role, UUID.randomUUID());
    }

    static User userInOrg(Role role, UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }
}
