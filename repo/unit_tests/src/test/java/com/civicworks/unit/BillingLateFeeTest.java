package com.civicworks.unit;

import com.civicworks.domain.entity.Bill;
import com.civicworks.domain.entity.LateFeeEvent;
import com.civicworks.domain.enums.BillStatus;
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
 * Unit tests for BillingService.applyLateFee():
 *  - 5% fee below $50 cap
 *  - 5% fee capped at $50 (5000 cents)
 *  - grace period guard: bill still within 10-day window
 *  - idempotency: second call throws 409 (already applied)
 *  - PAID bill rejected with 422
 *  - CANCELLED bill rejected with 422
 *  - version conflict throws VersionConflictException
 *  - bill status transitions to OVERDUE
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingLateFeeTest {

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

    @BeforeEach
    void setUp() {
        billingService = new BillingService(
                feeItemRepository, billingRunRepository, billRepository,
                billDiscountRepository, lateFeeEventRepository, accountRepository,
                usageRecordRepository, auditService, quartzSchedulerConfig,
                notificationService, userRepository);

        when(lateFeeEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── fee calculation ───────────────────────────────────────────────────────

    @Test
    void fivePercent_belowCap_addedToBalance() {
        Bill bill = openBill(50_000L); // $500 → 5% = $25 (2500 cents)
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

        billingService.applyLateFee(billId, 0, null);

        ArgumentCaptor<Bill> saved = ArgumentCaptor.forClass(Bill.class);
        verify(billRepository).save(saved.capture());
        assertThat(saved.getValue().getBalanceCents()).isEqualTo(52_500L);
    }

    @Test
    void fivePercent_aboveCap_cappedAtFiftyCents() {
        Bill bill = openBill(200_000L); // $2000 → 5% = $100, capped at $50
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

        billingService.applyLateFee(billId, 0, null);

        ArgumentCaptor<Bill> saved = ArgumentCaptor.forClass(Bill.class);
        verify(billRepository).save(saved.capture());
        assertThat(saved.getValue().getBalanceCents()).isEqualTo(205_000L); // +5000 cap
    }

    @Test
    void fivePercent_exactlyAtCap_notExceeded() {
        Bill bill = openBill(100_000L); // $1000 → 5% = $50 exactly at cap
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

        billingService.applyLateFee(billId, 0, null);

        ArgumentCaptor<Bill> saved = ArgumentCaptor.forClass(Bill.class);
        verify(billRepository).save(saved.capture());
        assertThat(saved.getValue().getBalanceCents()).isEqualTo(105_000L);
    }

    @Test
    void lateFeeEvent_persisted_withCorrectAmount() {
        Bill bill = openBill(20_000L); // 5% = 1000 cents
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

        billingService.applyLateFee(billId, 0, null);

        ArgumentCaptor<LateFeeEvent> evt = ArgumentCaptor.forClass(LateFeeEvent.class);
        verify(lateFeeEventRepository).save(evt.capture());
        assertThat(evt.getValue().getLateFeeCents()).isEqualTo(1_000L);
    }

    // ── status transition ─────────────────────────────────────────────────────

    @Test
    void openBill_becomesOverdue_afterLateFee() {
        Bill bill = openBill(10_000L);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

        billingService.applyLateFee(billId, 0, null);

        ArgumentCaptor<Bill> saved = ArgumentCaptor.forClass(Bill.class);
        verify(billRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(BillStatus.OVERDUE);
    }

    // ── guard: already applied ────────────────────────────────────────────────

    @Test
    void alreadyApplied_throwsBusinessException_409() {
        Bill bill = openBill(10_000L);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(true);

        assertThatThrownBy(() -> billingService.applyLateFee(billId, 0, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already applied");
    }

    // ── guard: grace period ───────────────────────────────────────────────────

    @Test
    void withinGracePeriod_throwsBusinessException() {
        Bill bill = openBill(10_000L);
        bill.setDueDate(LocalDate.now().minusDays(5)); // only 5 days past due, need 10
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

        assertThatThrownBy(() -> billingService.applyLateFee(billId, 0, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("grace period");
    }

    @Test
    void exactlyAtGracePeriodBoundary_feeApplied() {
        Bill bill = openBill(10_000L);
        bill.setDueDate(LocalDate.now().minusDays(10)); // exactly 10 days past, grace ends today
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

        // due_date + 10 days = today, today is NOT before today → should proceed
        assertThatCode(() -> billingService.applyLateFee(billId, 0, null))
                .doesNotThrowAnyException();
    }

    // ── guard: terminal status ────────────────────────────────────────────────

    @Test
    void paidBill_throwsBusinessException() {
        Bill bill = billWithStatus(BillStatus.PAID, 5_000L);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

        assertThatThrownBy(() -> billingService.applyLateFee(billId, 0, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PAID");
    }

    @Test
    void cancelledBill_throwsBusinessException() {
        Bill bill = billWithStatus(BillStatus.CANCELLED, 5_000L);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

        assertThatThrownBy(() -> billingService.applyLateFee(billId, 0, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("CANCELLED");
    }

    // ── version conflict ─────────────────────────────────────────────────────

    @Test
    void staleVersion_throwsVersionConflictException() {
        Bill bill = openBill(10_000L); // version = 0 via setVersion
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

        assertThatThrownBy(() -> billingService.applyLateFee(billId, 5, null))
                .isInstanceOf(VersionConflictException.class);
    }

    @Test
    void nullVersion_throwsVersionConflictException() {
        Bill bill = openBill(10_000L);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(lateFeeEventRepository.existsByBillId(billId)).thenReturn(false);

        assertThatThrownBy(() -> billingService.applyLateFee(billId, null, null))
                .isInstanceOf(VersionConflictException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Bill openBill(long balanceCents) {
        Bill bill = new Bill();
        bill.setId(billId);
        bill.setStatus(BillStatus.OPEN);
        bill.setBalanceCents(balanceCents);
        bill.setAmountCents(balanceCents);
        // due 60 days ago → well past grace period
        bill.setDueDate(LocalDate.now().minusDays(60));
        setVersion(bill, 0);
        return bill;
    }

    private Bill billWithStatus(BillStatus status, long balanceCents) {
        Bill bill = openBill(balanceCents);
        bill.setStatus(status);
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
