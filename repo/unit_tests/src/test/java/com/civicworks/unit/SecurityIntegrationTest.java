package com.civicworks.unit;

import com.civicworks.domain.entity.*;
import com.civicworks.domain.enums.*;
import com.civicworks.dto.AcceptOrderRequest;
import com.civicworks.dto.ApplyDiscountRequest;
import com.civicworks.exception.ResourceNotFoundException;
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
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Consolidated security integration tests covering all three attack vectors:
 *  - Cross-tenant billing mutation
 *  - Cross-tenant content access
 *  - Forced dispatch hijack
 *  - Correct driver acceptance (positive control)
 *
 * Every test constructs the full service with mocked repositories and
 * exercises the service layer directly — the same path the controllers use.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecurityIntegrationTest {

    // ── shared org ids ───────────────────────────────────────────────────
    static final UUID ORG_A = UUID.randomUUID();
    static final UUID ORG_B = UUID.randomUUID();

    // ═══════════════════════════════════════════════════════════════════════
    //  Cross-tenant billing
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class CrossTenantBilling {

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

        BillingService billingService;
        final UUID billId = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            billingService = new BillingService(
                    feeItemRepository, billingRunRepository, billRepository,
                    billDiscountRepository, lateFeeEventRepository, accountRepository,
                    usageRecordRepository, auditService, quartzSchedulerConfig,
                    notificationService, userRepository);
            when(billRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(lateFeeEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(billDiscountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        void clerkOrgA_applyLateFee_billOrgB_fails() {
            when(billRepository.findByIdAndOrganizationId(billId, ORG_A)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> billingService.applyLateFee(billId, 0, clerkIn(ORG_A)))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(billRepository, never()).save(any());
        }

        @Test
        void clerkOrgA_applyDiscount_billOrgB_fails() {
            when(billRepository.findByIdAndOrganizationId(billId, ORG_A)).thenReturn(Optional.empty());

            ApplyDiscountRequest req = new ApplyDiscountRequest();
            req.setDiscountType(DiscountType.FIXED);
            req.setValueBasisPointsOrCents(500L);
            req.setEntityVersion(0);

            assertThatThrownBy(() -> billingService.applyDiscount(billId, req, clerkIn(ORG_A)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void admin_applyLateFee_anyBill_succeeds() {
            Bill bill = billIn(ORG_B);
            when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
            when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

            assertThatCode(() -> billingService.applyLateFee(billId, 0, adminIn(ORG_A)))
                    .doesNotThrowAnyException();
        }

        private Bill billIn(UUID orgId) {
            Bill b = new Bill();
            b.setId(billId);
            b.setOrganizationId(orgId);
            b.setStatus(BillStatus.OPEN);
            b.setBalanceCents(10_000L);
            b.setAmountCents(10_000L);
            b.setDueDate(LocalDate.now().minusDays(60));
            setVersion(b, 0);
            return b;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Cross-tenant content
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class CrossTenantContent {

        @Mock ContentItemRepository contentItemRepository;
        @Mock ContentPublishSnapshotRepository snapshotRepository;
        @Mock AuditService auditService;
        @Mock QuartzSchedulerConfig quartzSchedulerConfig;
        @Mock NotificationService notificationService;

        ContentService contentService;
        final UUID itemId = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            contentService = new ContentService(
                    contentItemRepository, snapshotRepository, auditService,
                    quartzSchedulerConfig, new HtmlSanitizerService(), notificationService);
        }

        @Test
        void editorOrgA_readItem_orgB_fails() {
            when(contentItemRepository.findByIdAndOrganizationId(itemId, ORG_A))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> contentService.findById(itemId, editorIn(ORG_A)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void editorOrgA_publishItem_orgB_fails() {
            ContentItem item = itemIn(ORG_B);
            when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

            assertThatThrownBy(() -> contentService.publishItem(itemId, 0, editorIn(ORG_A)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void admin_readItem_anyOrg_succeeds() {
            ContentItem item = itemIn(ORG_B);
            when(contentItemRepository.findById(itemId)).thenReturn(Optional.of(item));

            assertThatCode(() -> contentService.findById(itemId, adminIn(ORG_A)))
                    .doesNotThrowAnyException();
        }

        private ContentItem itemIn(UUID orgId) {
            ContentItem item = new ContentItem();
            item.setId(itemId);
            item.setTitle("Test");
            item.setState(ContentState.DRAFT);
            Organization org = new Organization();
            org.setId(orgId);
            item.setOrganization(org);
            return item;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Forced dispatch hijack
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ForcedDispatchHijack {

        @Mock DispatchOrderRepository dispatchOrderRepository;
        @Mock ZoneRepository zoneRepository;
        @Mock ZoneQueueRepository zoneQueueRepository;
        @Mock DriverOnlineSessionRepository driverOnlineSessionRepository;
        @Mock DriverCooldownRepository driverCooldownRepository;
        @Mock UserRepository userRepository;
        @Mock AuditService auditService;
        @Mock NotificationService notificationService;

        DispatchService dispatchService;
        final UUID orderId = UUID.randomUUID();
        final UUID driverA = UUID.randomUUID();
        final UUID driverB = UUID.randomUUID();

        @BeforeEach
        void setUp() {
            dispatchService = new DispatchService(
                    dispatchOrderRepository, zoneRepository, zoneQueueRepository,
                    driverOnlineSessionRepository, driverCooldownRepository,
                    userRepository, auditService, notificationService);
            stubEligible(driverA);
            stubEligible(driverB);
        }

        @Test
        void driverB_acceptsForcedOrderForDriverA_forbidden() {
            DispatchOrder order = forcedOrder(driverA);
            when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> dispatchService.acceptOrder(orderId, 0, driver(driverB), coords()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .isEqualTo(HttpStatus.FORBIDDEN));
            verify(dispatchOrderRepository, never()).save(any());
        }

        @Test
        void driverA_acceptsForcedOrderForDriverA_succeeds() {
            DispatchOrder order = forcedOrder(driverA);
            when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));
            when(dispatchOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            DispatchOrder accepted = dispatchService.acceptOrder(orderId, 0, driver(driverA), coords());
            assertThat(accepted.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
            assertThat(accepted.getAssignedDriverId()).isEqualTo(driverA);
        }

        @Test
        void reassign_before30min_rejected() {
            DispatchOrder order = forcedOrder(driverA);
            order.setAssignedAt(OffsetDateTime.now().minusMinutes(10));
            when(dispatchOrderRepository.findById(orderId)).thenReturn(Optional.of(order));

            assertThatThrownBy(() -> dispatchService.reassignOrder(orderId, 0, driverB, driver(driverA)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("30 minutes");
        }

        private DispatchOrder forcedOrder(UUID assignedTo) {
            DispatchOrder o = new DispatchOrder();
            o.setId(orderId);
            o.setMode(OrderMode.DISPATCHER_ASSIGNED);
            o.setStatus(OrderStatus.ASSIGNED);
            o.setAssignedDriverId(assignedTo);
            o.setForcedFlag(true);
            o.setPickupLat(BigDecimal.valueOf(37.77));
            o.setPickupLng(BigDecimal.valueOf(-122.41));
            return o;
        }

        private AcceptOrderRequest coords() {
            AcceptOrderRequest r = new AcceptOrderRequest();
            r.setDriverLat(37.77);
            r.setDriverLng(-122.41);
            return r;
        }

        private void stubEligible(UUID id) {
            User d = new User();
            d.setId(id);
            d.setRating(4.8);
            when(userRepository.findById(id)).thenReturn(Optional.of(d));
            when(driverOnlineSessionRepository.sumMinutesForDriverOnDate(eq(id), any(LocalDate.class))).thenReturn(60.0);
            when(driverCooldownRepository.existsByDriverIdAndCooldownUntilAfter(eq(id), any(OffsetDateTime.class))).thenReturn(false);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  AuthorizationService unit tests
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class AuthorizationServiceTests {

        @Test
        void nullActor_globalAccess() {
            assertThat(AuthorizationService.resolveOrgId(null)).isNull();
        }

        @Test
        void systemAdmin_globalAccess() {
            User admin = adminIn(ORG_A);
            assertThat(AuthorizationService.resolveOrgId(admin)).isNull();
        }

        @Test
        void clerk_scopedToOrg() {
            User clerk = clerkIn(ORG_A);
            assertThat(AuthorizationService.resolveOrgId(clerk)).isEqualTo(ORG_A);
        }

        @Test
        void checkOwnership_sameOrg_passes() {
            assertThatCode(() -> AuthorizationService.checkOwnership(ORG_A, clerkIn(ORG_A), "Bill", UUID.randomUUID()))
                    .doesNotThrowAnyException();
        }

        @Test
        void checkOwnership_differentOrg_throws404() {
            UUID entityId = UUID.randomUUID();
            assertThatThrownBy(() -> AuthorizationService.checkOwnership(ORG_B, clerkIn(ORG_A), "Bill", entityId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void checkOwnership_admin_alwaysPasses() {
            assertThatCode(() -> AuthorizationService.checkOwnership(ORG_B, adminIn(ORG_A), "Bill", UUID.randomUUID()))
                    .doesNotThrowAnyException();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Shared helpers
    // ═══════════════════════════════════════════════════════════════════════

    static User clerkIn(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.BILLING_CLERK);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }

    static User editorIn(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.CONTENT_EDITOR);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }

    static User adminIn(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.SYSTEM_ADMIN);
        Organization org = new Organization();
        org.setId(orgId);
        u.setOrganization(org);
        return u;
    }

    static User driver(UUID id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    static void setVersion(Object target, int version) {
        try {
            Field f = target.getClass().getDeclaredField("version");
            f.setAccessible(true);
            f.set(target, version);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
