package com.civicworks.unit;

import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.PaymentMethod;
import com.civicworks.domain.enums.Role;
import com.civicworks.dto.ShiftHandoverReportDTO;
import com.civicworks.dto.ShiftHandoverRequest;
import com.civicworks.repository.*;
import com.civicworks.service.AuditService;
import com.civicworks.service.NotificationService;
import com.civicworks.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies the report-grade ShiftHandoverReportDTO returned by processHandover:
 * - Named totals: totalCash, totalCheck, totalVoucher, totalOther
 * - submittedTotal vs expectedTotal
 * - delta calculation
 * - discrepancyFlag set correctly
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShiftHandoverSummaryTest {

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
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                billRepository, settlementRepository, paymentRepository,
                refundRepository, shiftHandoverRepository, shiftHandoverTotalRepository,
                discrepancyCaseRepository, userRepository, auditService, notificationService);

        when(shiftHandoverRepository.save(any())).thenAnswer(inv -> {
            var h = inv.getArgument(0);
            try {
                var f = h.getClass().getDeclaredField("id");
                f.setAccessible(true);
                if (f.get(h) == null) f.set(h, UUID.randomUUID());
            } catch (Exception ignored) {}
            return h;
        });
        when(shiftHandoverTotalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(discrepancyCaseRepository.save(any())).thenAnswer(inv -> {
            var c = inv.getArgument(0);
            try {
                var f = c.getClass().getDeclaredField("id");
                f.setAccessible(true);
                if (f.get(c) == null) f.set(c, UUID.randomUUID());
            } catch (Exception ignored) {}
            return c;
        });
    }

    @Test
    void report_noDiscrepancy_zeroDelta_namedTotalsCorrect() {
        when(paymentRepository.sumAmountByMethodForShift("S1"))
                .thenReturn(List.<Object[]>of(new Object[]{PaymentMethod.CASH, 5000L}));

        ShiftHandoverReportDTO report = paymentService.processHandover("S1", cashRequest(5000L), actor());

        assertThat(report.getHandoverId()).isNotNull();
        assertThat(report.getShiftId()).isEqualTo("S1");
        assertThat(report.getStatus()).isEqualTo("RECORDED");
        assertThat(report.getTotalCash()).isEqualTo(5000L);
        assertThat(report.getTotalCheck()).isEqualTo(0L);
        assertThat(report.getTotalVoucher()).isEqualTo(0L);
        assertThat(report.getTotalOther()).isEqualTo(0L);
        assertThat(report.getSubmittedTotal()).isEqualTo(5000L);
        assertThat(report.getExpectedTotal()).isEqualTo(5000L);
        assertThat(report.getDelta()).isEqualTo(0L);
        assertThat(report.isDiscrepancyFlag()).isFalse();
        assertThat(report.getDiscrepancyCaseId()).isNull();
    }

    @Test
    void report_largeDiscrepancy_flagsCase() {
        when(paymentRepository.sumAmountByMethodForShift("S2"))
                .thenReturn(List.<Object[]>of(new Object[]{PaymentMethod.CASH, 5000L}));
        when(userRepository.findByRole(Role.BILLING_CLERK)).thenReturn(List.of());

        ShiftHandoverReportDTO report = paymentService.processHandover("S2", cashRequest(7000L), actor());

        assertThat(report.getSubmittedTotal()).isEqualTo(7000L);
        assertThat(report.getExpectedTotal()).isEqualTo(5000L);
        assertThat(report.getDelta()).isEqualTo(2000L);
        assertThat(report.isDiscrepancyFlag()).isTrue();
        assertThat(report.getDiscrepancyCaseId()).isNotNull();
    }

    @Test
    void report_multiplePaymentMethods_allNamedTotals() {
        when(paymentRepository.sumAmountByMethodForShift("S3"))
                .thenReturn(List.<Object[]>of(
                        new Object[]{PaymentMethod.CASH, 3000L},
                        new Object[]{PaymentMethod.CHECK, 2000L}));

        ShiftHandoverRequest request = new ShiftHandoverRequest();
        ShiftHandoverRequest.MethodTotal mt1 = new ShiftHandoverRequest.MethodTotal();
        mt1.setPaymentMethod(PaymentMethod.CASH);
        mt1.setTotalAmountCents(3000L);
        ShiftHandoverRequest.MethodTotal mt2 = new ShiftHandoverRequest.MethodTotal();
        mt2.setPaymentMethod(PaymentMethod.CHECK);
        mt2.setTotalAmountCents(2000L);
        request.setSubmittedTotals(List.of(mt1, mt2));

        ShiftHandoverReportDTO report = paymentService.processHandover("S3", request, actor());

        assertThat(report.getTotalCash()).isEqualTo(3000L);
        assertThat(report.getTotalCheck()).isEqualTo(2000L);
        assertThat(report.getTotalVoucher()).isEqualTo(0L);
        assertThat(report.getSubmittedTotal()).isEqualTo(5000L);
        assertThat(report.getExpectedTotal()).isEqualTo(5000L);
        assertThat(report.getDelta()).isEqualTo(0L);
        assertThat(report.isDiscrepancyFlag()).isFalse();
        assertThat(report.getSubmittedByMethod()).containsEntry("CASH", 3000L);
        assertThat(report.getSubmittedByMethod()).containsEntry("CHECK", 2000L);
        assertThat(report.getPostedByMethod()).containsEntry("CASH", 3000L);
        assertThat(report.getPostedByMethod()).containsEntry("CHECK", 2000L);
    }

    @Test
    void report_negativeDiscrepancy_detectedAndFlagged() {
        // Submitted 3000, posted 5000 → delta = -2000
        when(paymentRepository.sumAmountByMethodForShift("S4"))
                .thenReturn(List.<Object[]>of(new Object[]{PaymentMethod.CASH, 5000L}));
        when(userRepository.findByRole(Role.BILLING_CLERK)).thenReturn(List.of());

        ShiftHandoverReportDTO report = paymentService.processHandover("S4", cashRequest(3000L), actor());

        assertThat(report.getDelta()).isEqualTo(-2000L);
        assertThat(report.isDiscrepancyFlag()).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private ShiftHandoverRequest cashRequest(long amountCents) {
        ShiftHandoverRequest request = new ShiftHandoverRequest();
        ShiftHandoverRequest.MethodTotal mt = new ShiftHandoverRequest.MethodTotal();
        mt.setPaymentMethod(PaymentMethod.CASH);
        mt.setTotalAmountCents(amountCents);
        request.setSubmittedTotals(List.of(mt));
        return request;
    }

    private User actor() {
        User u = new User();
        u.setId(actorId);
        u.setRole(Role.SYSTEM_ADMIN);
        return u;
    }
}
