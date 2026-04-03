package com.civicworks.unit;

import com.civicworks.domain.entity.Bill;
import com.civicworks.domain.enums.BillStatus;
import com.civicworks.domain.enums.DiscountType;
import com.civicworks.dto.ApplyDiscountRequest;
import com.civicworks.exception.BusinessException;
import com.civicworks.exception.VersionConflictException;
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

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BillingService.applyDiscount():
 *  - PERCENTAGE discount calculated in basis points (100 bp = 1%)
 *  - FLAT discount subtracted directly in cents
 *  - discount does not push balance below 0
 *  - second discount on same bill → 422 DISCOUNT_ALREADY_APPLIED
 *  - version conflict on stale/null version
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingDiscountTest {

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
    @Mock com.civicworks.repository.UserRepository userRepository;

    private BillingService billingService;
    private final UUID billId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        billingService = new BillingService(
                feeItemRepository, billingRunRepository, billRepository,
                billDiscountRepository, lateFeeEventRepository, accountRepository,
                usageRecordRepository, auditService, quartzSchedulerConfig,
                notificationService, userRepository);

        when(billDiscountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billDiscountRepository.existsByBillId(billId)).thenReturn(false);
    }

    // ── PERCENTAGE discount ───────────────────────────────────────────────────

    @Test
    void percentageDiscount_tenPercent_reducesBalance() {
        // 1000 basis points = 10%
        Bill bill = openBill(50_000L);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));

        applyDiscount(DiscountType.PERCENTAGE, 1000L);

        ArgumentCaptor<Bill> saved = ArgumentCaptor.forClass(Bill.class);
        verify(billRepository).save(saved.capture());
        // 10% of 50,000 = 5,000 → new balance = 45,000
        assertThat(saved.getValue().getBalanceCents()).isEqualTo(45_000L);
    }

    @Test
    void percentageDiscount_hundredPercent_zeroesBalance() {
        Bill bill = openBill(30_000L);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));

        applyDiscount(DiscountType.PERCENTAGE, 10_000L); // 100%

        ArgumentCaptor<Bill> saved = ArgumentCaptor.forClass(Bill.class);
        verify(billRepository).save(saved.capture());
        assertThat(saved.getValue().getBalanceCents()).isEqualTo(0L);
    }

    @Test
    void percentageDiscount_onePercent_roundedDown() {
        Bill bill = openBill(1_001L); // 1% = 10.01, truncated to 10
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));

        applyDiscount(DiscountType.PERCENTAGE, 100L); // 1%

        ArgumentCaptor<Bill> saved = ArgumentCaptor.forClass(Bill.class);
        verify(billRepository).save(saved.capture());
        assertThat(saved.getValue().getBalanceCents()).isEqualTo(991L); // 1001 - 10
    }

    // ── FLAT discount ─────────────────────────────────────────────────────────

    @Test
    void flatDiscount_subtractsCentsDirectly() {
        Bill bill = openBill(10_000L);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));

        applyDiscount(DiscountType.FIXED, 2_500L);

        ArgumentCaptor<Bill> saved = ArgumentCaptor.forClass(Bill.class);
        verify(billRepository).save(saved.capture());
        assertThat(saved.getValue().getBalanceCents()).isEqualTo(7_500L);
    }

    @Test
    void flatDiscount_exceedingBalance_zeroesNotNegative() {
        Bill bill = openBill(500L);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));

        applyDiscount(DiscountType.FIXED, 9_999L); // more than balance

        ArgumentCaptor<Bill> saved = ArgumentCaptor.forClass(Bill.class);
        verify(billRepository).save(saved.capture());
        assertThat(saved.getValue().getBalanceCents()).isEqualTo(0L);
    }

    // ── single-discount guard ─────────────────────────────────────────────────

    @Test
    void secondDiscount_throwsBusinessException() {
        Bill bill = openBill(10_000L);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(billDiscountRepository.existsByBillId(billId)).thenReturn(true); // already applied

        assertThatThrownBy(() -> applyDiscount(DiscountType.FIXED, 500L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already been applied");
    }

    // ── version conflict ─────────────────────────────────────────────────────

    @Test
    void staleVersion_throwsVersionConflict() {
        Bill bill = openBill(10_000L); // version = 0
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));

        var req = discountRequest(DiscountType.FIXED, 500L);
        req.setEntityVersion(9);

        assertThatThrownBy(() -> billingService.applyDiscount(billId, req, actorUser()))
                .isInstanceOf(VersionConflictException.class);
    }

    @Test
    void nullVersion_throwsVersionConflict() {
        Bill bill = openBill(10_000L);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));

        var req = discountRequest(DiscountType.FIXED, 500L);
        req.setEntityVersion(null);

        assertThatThrownBy(() -> billingService.applyDiscount(billId, req, actorUser()))
                .isInstanceOf(VersionConflictException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void applyDiscount(DiscountType type, long value) {
        billingService.applyDiscount(billId, discountRequest(type, value), actorUser());
    }

    private ApplyDiscountRequest discountRequest(DiscountType type, long value) {
        ApplyDiscountRequest req = new ApplyDiscountRequest();
        req.setDiscountType(type);
        req.setValueBasisPointsOrCents(value);
        req.setEntityVersion(0);
        return req;
    }

    private com.civicworks.domain.entity.User actorUser() {
        com.civicworks.domain.entity.User u = new com.civicworks.domain.entity.User();
        u.setId(actorId);
        u.setRole(com.civicworks.domain.enums.Role.SYSTEM_ADMIN);
        return u;
    }

    private Bill openBill(long balanceCents) {
        Bill bill = new Bill();
        bill.setId(billId);
        bill.setStatus(BillStatus.OPEN);
        bill.setBalanceCents(balanceCents);
        bill.setAmountCents(balanceCents);
        bill.setDueDate(LocalDate.now().plusDays(30));
        setVersion(bill, 0);
        return bill;
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
