package com.civicworks.unit;

import com.civicworks.domain.entity.Bill;
import com.civicworks.domain.entity.Organization;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.BillStatus;
import com.civicworks.domain.enums.Role;
import com.civicworks.exception.ResourceNotFoundException;
import com.civicworks.repository.*;
import com.civicworks.scheduler.QuartzSchedulerConfig;
import com.civicworks.service.AuditService;
import com.civicworks.service.BillingService;
import com.civicworks.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies tenant isolation: a BILLING_CLERK from org-A cannot apply a
 * late fee or discount to a bill belonging to org-B.  The call must fail
 * with ResourceNotFoundException (presented as 404 to avoid leaking
 * existence of cross-org resources).
 *
 * Also verifies that SYSTEM_ADMIN bypasses the org check.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossTenantBillingTest {

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

    private final UUID billId = UUID.randomUUID();
    private final UUID orgA = UUID.randomUUID();
    private final UUID orgB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        billingService = new BillingService(
                feeItemRepository, billingRunRepository, billRepository,
                billDiscountRepository, lateFeeEventRepository, accountRepository,
                usageRecordRepository, auditService, quartzSchedulerConfig,
                notificationService, userRepository);

        when(billRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lateFeeEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void clerkFromOrgA_cannotApplyLateFee_toBillInOrgB() {
        Bill bill = billInOrg(orgB);
        // org-scoped lookup returns empty → ResourceNotFoundException
        when(billRepository.findByIdAndOrganizationId(billId, orgA)).thenReturn(Optional.empty());

        User clerkA = clerkInOrg(orgA);

        assertThatThrownBy(() -> billingService.applyLateFee(billId, 0, clerkA))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(billRepository, never()).save(any());
    }

    @Test
    void clerkFromOrgB_canApplyLateFee_toBillInOrgB() {
        Bill bill = billInOrg(orgB);
        when(billRepository.findByIdAndOrganizationId(billId, orgB)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

        User clerkB = clerkInOrg(orgB);

        assertThatCode(() -> billingService.applyLateFee(billId, 0, clerkB))
                .doesNotThrowAnyException();
    }

    @Test
    void systemAdmin_canApplyLateFee_toAnyBill() {
        Bill bill = billInOrg(orgB);
        // SYSTEM_ADMIN uses findById (no org filter)
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

        User admin = adminInOrg(orgA);

        assertThatCode(() -> billingService.applyLateFee(billId, 0, admin))
                .doesNotThrowAnyException();
    }

    @Test
    void clerkFromOrgA_cannotApplyDiscount_toBillInOrgB() {
        when(billRepository.findByIdAndOrganizationId(billId, orgA)).thenReturn(Optional.empty());

        User clerkA = clerkInOrg(orgA);
        var req = new com.civicworks.dto.ApplyDiscountRequest();
        req.setDiscountType(com.civicworks.domain.enums.DiscountType.FIXED);
        req.setValueBasisPointsOrCents(500L);
        req.setEntityVersion(0);

        assertThatThrownBy(() -> billingService.applyDiscount(billId, req, clerkA))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Bill billInOrg(UUID orgId) {
        Bill bill = new Bill();
        bill.setId(billId);
        bill.setOrganizationId(orgId);
        bill.setStatus(BillStatus.OPEN);
        bill.setBalanceCents(10_000L);
        bill.setAmountCents(10_000L);
        bill.setDueDate(LocalDate.now().minusDays(60));
        setVersion(bill, 0);
        return bill;
    }

    private User clerkInOrg(UUID orgId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(Role.BILLING_CLERK);
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
