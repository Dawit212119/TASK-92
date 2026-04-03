package com.civicworks.unit;

import com.civicworks.domain.entity.*;
import com.civicworks.domain.enums.*;
import com.civicworks.dto.CreateSettlementRequest;
import com.civicworks.dto.ShiftHandoverRequest;
import com.civicworks.repository.*;
import com.civicworks.service.AuditService;
import com.civicworks.service.NotificationService;
import com.civicworks.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Boundary tests for critical business rules:
 *  1) Discrepancy threshold: delta >$1.00 (100 cents) triggers case; ≤$1.00 does not
 *  2) Split-even settlement rounding: indivisible cents allocated correctly
 *  3) Search history 90-day cleanup retention
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessRuleBoundaryTest {

    // ═══════════════════════════════════════════════════════════════════════
    //  1) Discrepancy threshold boundary
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class DiscrepancyThreshold {

        @Mock BillRepository billRepository;
        @Mock SettlementRepository settlementRepository;
        @Mock PaymentRepository paymentRepository;
        @Mock RefundRepository refundRepository;
        @Mock ShiftHandoverRepository shiftHandoverRepository;
        @Mock ShiftHandoverTotalRepository shiftHandoverTotalRepository;
        @Mock DiscrepancyCaseRepository discrepancyCaseRepository;
        @Mock UserRepository userRepository;
        @Mock AuditService auditService;
        @Mock NotificationService notificationService;

        private PaymentService paymentService;
        private User actor;

        @BeforeEach
        void setUp() {
            paymentService = new PaymentService(
                    billRepository, settlementRepository, paymentRepository,
                    refundRepository, shiftHandoverRepository, shiftHandoverTotalRepository,
                    discrepancyCaseRepository, userRepository, auditService, notificationService);

            actor = new User();
            actor.setId(UUID.randomUUID());
            actor.setRole(Role.SYSTEM_ADMIN);

            when(shiftHandoverRepository.save(any())).thenAnswer(inv -> {
                ShiftHandover h = inv.getArgument(0);
                if (h.getId() == null) h.setId(UUID.randomUUID());
                return h;
            });
            when(shiftHandoverTotalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(discrepancyCaseRepository.save(any())).thenAnswer(inv -> {
                DiscrepancyCase dc = inv.getArgument(0);
                if (dc.getId() == null) dc.setId(UUID.randomUUID());
                return dc;
            });
            when(userRepository.findByRole(Role.BILLING_CLERK)).thenReturn(List.of());
        }

        @Test
        void deltaExactly100cents_noDiscrepancyCreated() {
            // Submitted 200 cents, posted 100 cents → delta = +100 (exactly $1.00)
            stubPostedPayments("shift-1", PaymentMethod.CASH, 100L);
            ShiftHandoverRequest req = handoverRequest(PaymentMethod.CASH, 200L);

            paymentService.processHandover("shift-1", req, actor);

            verify(discrepancyCaseRepository, never()).save(any());
        }

        @Test
        void delta101cents_discrepancyCreated() {
            // Submitted 201 cents, posted 100 cents → delta = +101 (>$1.00)
            stubPostedPayments("shift-2", PaymentMethod.CASH, 100L);
            ShiftHandoverRequest req = handoverRequest(PaymentMethod.CASH, 201L);

            paymentService.processHandover("shift-2", req, actor);

            verify(discrepancyCaseRepository).save(any());
        }

        @Test
        void negativeDelta101cents_discrepancyCreated() {
            // Submitted 0 cents, posted 101 cents → delta = -101 (<-$1.00)
            stubPostedPayments("shift-3", PaymentMethod.CHECK, 101L);
            ShiftHandoverRequest req = handoverRequest(PaymentMethod.CHECK, 0L);

            paymentService.processHandover("shift-3", req, actor);

            verify(discrepancyCaseRepository).save(any());
        }

        @Test
        void deltaZero_noDiscrepancyCreated() {
            stubPostedPayments("shift-4", PaymentMethod.CASH, 500L);
            ShiftHandoverRequest req = handoverRequest(PaymentMethod.CASH, 500L);

            paymentService.processHandover("shift-4", req, actor);

            verify(discrepancyCaseRepository, never()).save(any());
        }

        private void stubPostedPayments(String shiftId, PaymentMethod method, long amount) {
            List<Object[]> result = new java.util.ArrayList<>();
            result.add(new Object[]{method, amount});
            when(paymentRepository.sumAmountByMethodForShift(shiftId)).thenReturn(result);
        }

        private ShiftHandoverRequest handoverRequest(PaymentMethod method, long amount) {
            ShiftHandoverRequest req = new ShiftHandoverRequest();
            ShiftHandoverRequest.MethodTotal mt = new ShiftHandoverRequest.MethodTotal();
            mt.setPaymentMethod(method);
            mt.setTotalAmountCents(amount);
            req.setSubmittedTotals(List.of(mt));
            return req;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  2) Split-even settlement rounding
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class SplitEvenRounding {

        @Mock BillRepository billRepository;
        @Mock SettlementRepository settlementRepository;
        @Mock PaymentRepository paymentRepository;
        @Mock RefundRepository refundRepository;
        @Mock ShiftHandoverRepository shiftHandoverRepository;
        @Mock ShiftHandoverTotalRepository shiftHandoverTotalRepository;
        @Mock DiscrepancyCaseRepository discrepancyCaseRepository;
        @Mock UserRepository userRepository;
        @Mock AuditService auditService;
        @Mock NotificationService notificationService;

        private PaymentService paymentService;
        private User actor;

        @BeforeEach
        void setUp() {
            paymentService = new PaymentService(
                    billRepository, settlementRepository, paymentRepository,
                    refundRepository, shiftHandoverRepository, shiftHandoverTotalRepository,
                    discrepancyCaseRepository, userRepository, auditService, notificationService);

            actor = new User();
            actor.setId(UUID.randomUUID());
            actor.setRole(Role.SYSTEM_ADMIN);

            when(settlementRepository.save(any())).thenAnswer(inv -> {
                Settlement s = inv.getArgument(0);
                if (s.getId() == null) s.setId(UUID.randomUUID());
                return s;
            });
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(billRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        void splitEven_100cents_3payers_correctRounding() {
            // 100 cents / 3 = 33 each, remainder 1 → first payer gets 34
            Bill bill = openBill(100L);
            when(billRepository.findById(bill.getId())).thenReturn(Optional.of(bill));

            CreateSettlementRequest req = new CreateSettlementRequest();
            req.setSettlementMode(SettlementMode.SPLIT_EVEN);
            req.setSplitCount(3);
            req.setPaymentMethod(PaymentMethod.CASH);
            req.setEntityVersion(0);

            paymentService.createSettlement(bill.getId(), req, actor);

            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository, times(3)).save(captor.capture());

            List<Payment> payments = captor.getAllValues();
            long total = payments.stream().mapToLong(Payment::getAmountCents).sum();
            assertThat(total).isEqualTo(100L);

            // First payer gets the remainder
            assertThat(payments.get(0).getAmountCents()).isEqualTo(34L);
            assertThat(payments.get(1).getAmountCents()).isEqualTo(33L);
            assertThat(payments.get(2).getAmountCents()).isEqualTo(33L);
        }

        @Test
        void splitEven_1cent_2payers_correctRounding() {
            // 1 cent / 2 = 0 each, remainder 1 → first payer gets 1
            Bill bill = openBill(1L);
            when(billRepository.findById(bill.getId())).thenReturn(Optional.of(bill));

            CreateSettlementRequest req = new CreateSettlementRequest();
            req.setSettlementMode(SettlementMode.SPLIT_EVEN);
            req.setSplitCount(2);
            req.setPaymentMethod(PaymentMethod.CASH);
            req.setEntityVersion(0);

            paymentService.createSettlement(bill.getId(), req, actor);

            ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
            verify(paymentRepository, times(2)).save(captor.capture());

            List<Payment> payments = captor.getAllValues();
            long total = payments.stream().mapToLong(Payment::getAmountCents).sum();
            assertThat(total).isEqualTo(1L);
            assertThat(payments.get(0).getAmountCents()).isEqualTo(1L);
            assertThat(payments.get(1).getAmountCents()).isEqualTo(0L);
        }

        private Bill openBill(long balanceCents) {
            Bill bill = new Bill();
            bill.setId(UUID.randomUUID());
            bill.setStatus(BillStatus.OPEN);
            bill.setBalanceCents(balanceCents);
            bill.setAmountCents(balanceCents);
            try {
                java.lang.reflect.Field f = Bill.class.getDeclaredField("version");
                f.setAccessible(true);
                f.set(bill, 0);
            } catch (Exception ignored) {}
            return bill;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  3) Search history 90-day cleanup
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class SearchHistoryCleanup {

        @Mock SearchHistoryRepository searchHistoryRepository;
        @Mock com.civicworks.repository.ContentItemRepository contentItemRepository;
        @Mock com.civicworks.service.ContentService contentService;

        @Test
        void cleanupOldHistory_uses90DayCutoff() {
            when(searchHistoryRepository.deleteByCreatedAtBefore(any(OffsetDateTime.class))).thenReturn(5);

            com.civicworks.service.SearchService searchService =
                    new com.civicworks.service.SearchService(contentService, contentItemRepository, searchHistoryRepository);
            int deleted = searchService.cleanupOldHistory();

            assertThat(deleted).isEqualTo(5);

            ArgumentCaptor<OffsetDateTime> captor = ArgumentCaptor.forClass(OffsetDateTime.class);
            verify(searchHistoryRepository).deleteByCreatedAtBefore(captor.capture());

            OffsetDateTime cutoff = captor.getValue();
            // Cutoff should be approximately 90 days ago (allow 1-minute tolerance)
            OffsetDateTime expected = OffsetDateTime.now().minusDays(90);
            assertThat(cutoff).isBetween(expected.minusMinutes(1), expected.plusMinutes(1));
        }
    }
}
