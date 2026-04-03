package com.civicworks.version;

import com.civicworks.domain.entity.Bill;
import com.civicworks.domain.entity.Payment;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.BillStatus;
import com.civicworks.domain.enums.PaymentMethod;
import com.civicworks.domain.enums.SettlementMode;
import com.civicworks.dto.CreateRefundRequest;
import com.civicworks.dto.CreateSettlementRequest;
import com.civicworks.exception.VersionConflictException;
import com.civicworks.repository.*;
import com.civicworks.service.AuditService;
import com.civicworks.service.NotificationService;
import com.civicworks.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests proving optimistic-locking enforcement in PaymentService.
 * No Spring context needed — pure Mockito.
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceVersionTest {

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

    private final UUID billId    = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final User actor     = new User();

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                billRepository, settlementRepository, paymentRepository,
                refundRepository, shiftHandoverRepository, shiftHandoverTotalRepository,
                discrepancyCaseRepository, userRepository, auditService, notificationService);
        actor.setId(UUID.randomUUID());
        actor.setUsername("clerk");
        actor.setRole(com.civicworks.domain.enums.Role.SYSTEM_ADMIN);
    }

    // -----------------------------------------------------------------------
    // createSettlement — Bill version checks
    // -----------------------------------------------------------------------

    @Test
    void createSettlement_correctVersion_succeeds() {
        Bill bill = openBill(5);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));
        when(settlementRepository.save(any())).thenAnswer(inv -> {
            com.civicworks.domain.entity.Settlement s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateSettlementRequest req = fullSettlementRequest(5); // version matches
        assertThatCode(() -> paymentService.createSettlement(billId, req, actor))
                .doesNotThrowAnyException();
    }

    @Test
    void createSettlement_staleVersion_throws409() {
        Bill bill = openBill(5);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));

        CreateSettlementRequest req = fullSettlementRequest(3); // stale!
        assertThatThrownBy(() -> paymentService.createSettlement(billId, req, actor))
                .isInstanceOf(VersionConflictException.class)
                .satisfies(ex -> {
                    VersionConflictException vce = (VersionConflictException) ex;
                    assertThat(vce.getEntityType()).isEqualTo("Bill");
                    assertThat(vce.getEntityId()).isEqualTo(billId);
                    assertThat(vce.getServerVersion()).isEqualTo(5);
                    assertThat(vce.getStateSummary()).containsKey("status");
                    assertThat(vce.getStateSummary()).containsKey("balanceCents");
                });
    }

    @Test
    void createSettlement_nullVersion_throws409() {
        Bill bill = openBill(5);
        when(billRepository.findById(billId)).thenReturn(Optional.of(bill));

        CreateSettlementRequest req = fullSettlementRequest(null); // no version = last-write-wins attempt
        assertThatThrownBy(() -> paymentService.createSettlement(billId, req, actor))
                .isInstanceOf(VersionConflictException.class);
    }

    // -----------------------------------------------------------------------
    // createRefund — Payment version checks
    // -----------------------------------------------------------------------

    @Test
    void createRefund_correctVersion_succeeds() {
        Payment payment = payment(2, 10000L);
        Bill bill = openBill(1);
        bill.setId(payment.getBillId());
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(refundRepository.sumRefundsByPaymentId(paymentId)).thenReturn(0L);
        when(billRepository.findById(payment.getBillId())).thenReturn(Optional.of(bill));
        when(refundRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateRefundRequest req = refundRequest(2, 500L);
        assertThatCode(() -> paymentService.createRefund(paymentId, req, actor))
                .doesNotThrowAnyException();
    }

    @Test
    void createRefund_staleVersion_throws409() {
        Payment payment = payment(2, 10000L);
        Bill bill = openBill(1);
        bill.setId(payment.getBillId());
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        when(billRepository.findById(payment.getBillId())).thenReturn(Optional.of(bill));

        CreateRefundRequest req = refundRequest(1, 500L); // stale!
        assertThatThrownBy(() -> paymentService.createRefund(paymentId, req, actor))
                .isInstanceOf(VersionConflictException.class)
                .satisfies(ex -> {
                    VersionConflictException vce = (VersionConflictException) ex;
                    assertThat(vce.getEntityType()).isEqualTo("Payment");
                    assertThat(vce.getEntityId()).isEqualTo(paymentId);
                    assertThat(vce.getServerVersion()).isEqualTo(2);
                    assertThat(vce.getStateSummary()).containsKey("amountCents");
                });
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Bill openBill(int version) {
        Bill b = new Bill();
        b.setId(billId);
        setVersion(b, version);
        b.setStatus(BillStatus.OPEN);
        b.setBalanceCents(50000L);
        return b;
    }

    private Payment payment(int version, long amountCents) {
        Payment p = new Payment();
        p.setId(paymentId);
        setVersion(p, version);
        p.setBillId(UUID.randomUUID());
        p.setAmountCents(amountCents);
        p.setPaymentMethod(PaymentMethod.CASH);
        return p;
    }

    private CreateSettlementRequest fullSettlementRequest(Integer entityVersion) {
        CreateSettlementRequest r = new CreateSettlementRequest();
        r.setSettlementMode(SettlementMode.FULL);
        r.setPaymentMethod(PaymentMethod.CASH);
        r.setEntityVersion(entityVersion);
        return r;
    }

    private CreateRefundRequest refundRequest(Integer entityVersion, long cents) {
        CreateRefundRequest r = new CreateRefundRequest();
        r.setRefundAmountCents(cents);
        r.setEntityVersion(entityVersion);
        return r;
    }

    /** Reflectively set the @Version field on JPA entities (not exposed via setter). */
    private static void setVersion(Object entity, int version) {
        try {
            var field = entity.getClass().getDeclaredField("version");
            field.setAccessible(true);
            field.set(entity, version);
        } catch (Exception e) {
            throw new RuntimeException("Could not set version field on " + entity.getClass().getSimpleName(), e);
        }
    }
}
