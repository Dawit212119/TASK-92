package com.civicworks.service;

import com.civicworks.domain.entity.*;
import com.civicworks.domain.enums.BillStatus;
import com.civicworks.domain.enums.DiscrepancyStatus;
import com.civicworks.domain.enums.PaymentMethod;
import com.civicworks.domain.enums.Role;
import com.civicworks.domain.enums.SettlementMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.civicworks.dto.CreateRefundRequest;
import com.civicworks.dto.CreateSettlementRequest;
import com.civicworks.dto.ShiftHandoverReportDTO;
import com.civicworks.dto.ShiftHandoverRequest;
import com.civicworks.dto.ShiftHandoverSummary;
import com.civicworks.exception.BusinessException;
import com.civicworks.exception.ResourceNotFoundException;
import com.civicworks.exception.VersionConflictException;
import com.civicworks.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final BillRepository billRepository;
    private final SettlementRepository settlementRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final ShiftHandoverRepository shiftHandoverRepository;
    private final ShiftHandoverTotalRepository shiftHandoverTotalRepository;
    private final DiscrepancyCaseRepository discrepancyCaseRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public PaymentService(BillRepository billRepository,
                          SettlementRepository settlementRepository,
                          PaymentRepository paymentRepository,
                          RefundRepository refundRepository,
                          ShiftHandoverRepository shiftHandoverRepository,
                          ShiftHandoverTotalRepository shiftHandoverTotalRepository,
                          DiscrepancyCaseRepository discrepancyCaseRepository,
                          UserRepository userRepository,
                          AuditService auditService,
                          NotificationService notificationService) {
        this.billRepository = billRepository;
        this.settlementRepository = settlementRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.shiftHandoverRepository = shiftHandoverRepository;
        this.shiftHandoverTotalRepository = shiftHandoverTotalRepository;
        this.discrepancyCaseRepository = discrepancyCaseRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    @Transactional
    public Settlement createSettlement(UUID billId, CreateSettlementRequest request, User actor) {
        Bill bill = loadBillWithTenantCheck(billId, actor);

        requireVersion(request.getEntityVersion(), bill.getVersion(), "Bill", billId,
                Map.of("status", bill.getStatus().name(), "balanceCents", bill.getBalanceCents()));

        if (bill.getStatus() == BillStatus.PAID || bill.getStatus() == BillStatus.CANCELLED) {
            throw new BusinessException("Cannot create settlement for PAID or CANCELLED bill", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        Settlement settlement = new Settlement();
        settlement.setBillId(billId);
        settlement.setShiftId(request.getShiftId());
        settlement.setSettlementMode(request.getSettlementMode());
        Settlement savedSettlement = settlementRepository.save(settlement);

        long totalPaid = 0L;

        if (request.getSettlementMode() == SettlementMode.FULL) {
            Payment payment = new Payment();
            payment.setBillId(billId);
            payment.setSettlementId(savedSettlement.getId());
            payment.setPaymentMethod(request.getPaymentMethod() != null ? request.getPaymentMethod() : PaymentMethod.CASH);
            payment.setAmountCents(bill.getBalanceCents());
            payment.setPayerSeq(1);
            payment.setShiftId(request.getShiftId());
            paymentRepository.save(payment);
            totalPaid = bill.getBalanceCents();

        } else if (request.getSettlementMode() == SettlementMode.SPLIT_EVEN) {
            int n = request.getSplitCount();
            if (n <= 0) {
                throw new BusinessException("splitCount must be > 0 for SPLIT_EVEN mode", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            long total = bill.getBalanceCents();
            long base = total / n;
            long remainder = total % n;

            for (int seq = 1; seq <= n; seq++) {
                long amount = base + (seq == 1 ? remainder : 0L); // remainder to first payer
                Payment payment = new Payment();
                payment.setBillId(billId);
                payment.setSettlementId(savedSettlement.getId());
                payment.setPaymentMethod(request.getPaymentMethod() != null ? request.getPaymentMethod() : PaymentMethod.CASH);
                payment.setAmountCents(amount);
                payment.setPayerSeq(seq);
                payment.setShiftId(request.getShiftId());
                paymentRepository.save(payment);
                totalPaid += amount;
            }

        } else if (request.getSettlementMode() == SettlementMode.SPLIT_CUSTOM) {
            if (request.getAllocations() == null || request.getAllocations().isEmpty()) {
                throw new BusinessException("Allocations required for SPLIT_CUSTOM mode", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            long allocationTotal = request.getAllocations().stream()
                    .mapToLong(CreateSettlementRequest.PaymentAllocationEntry::getAmountCents).sum();
            if (allocationTotal != bill.getBalanceCents()) {
                throw new BusinessException(
                        "Allocation total " + allocationTotal + " cents does not match bill balance "
                                + bill.getBalanceCents() + " cents",
                        HttpStatus.UNPROCESSABLE_ENTITY, "ALLOCATION_TOTAL_MISMATCH");
            }
            for (CreateSettlementRequest.PaymentAllocationEntry entry : request.getAllocations()) {
                Payment payment = new Payment();
                payment.setBillId(billId);
                payment.setSettlementId(savedSettlement.getId());
                payment.setPaymentMethod(entry.getPaymentMethod());
                payment.setAmountCents(entry.getAmountCents());
                payment.setPayerSeq(entry.getPayerSeq());
                payment.setShiftId(request.getShiftId());
                paymentRepository.save(payment);
                totalPaid += entry.getAmountCents();
            }
        }

        long newBalance = bill.getBalanceCents() - totalPaid;
        bill.setBalanceCents(Math.max(0L, newBalance));
        bill.setStatus(newBalance <= 0 ? BillStatus.PAID : BillStatus.PARTIAL);
        billRepository.save(bill);

        MDC.put("billId", billId.toString());
        log.info("SETTLEMENT_CREATED mode={} totalPaid={}", request.getSettlementMode(), totalPaid);
        MDC.remove("billId");

        auditService.log(actor.getId(), "PAYMENT_POSTED", "bills/" + billId,
                Map.of("settlementId", savedSettlement.getId().toString(), "totalPaid", totalPaid));

        // Notify org users about the payment
        notifyOrgUsers(bill.getOrganizationId(), "PAYMENT_COMPLETED",
                "Payment posted for bill",
                "A payment of " + totalPaid + " cents has been posted for bill " + billId
                        + ". New balance: " + bill.getBalanceCents() + " cents.",
                "bills/" + billId);

        return savedSettlement;
    }

    @Transactional
    public Refund createRefund(UUID paymentId, CreateRefundRequest request, User actor) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        // Tenant isolation: verify the payment's bill is accessible to the actor's org
        loadBillWithTenantCheck(payment.getBillId(), actor);

        requireVersion(request.getEntityVersion(), payment.getVersion(), "Payment", paymentId,
                Map.of("amountCents", payment.getAmountCents(),
                       "paymentMethod", payment.getPaymentMethod() != null ? payment.getPaymentMethod().name() : "UNKNOWN",
                       "billId", payment.getBillId().toString()));

        long existingRefunds = refundRepository.sumRefundsByPaymentId(paymentId);
        if (existingRefunds + request.getRefundAmountCents() > payment.getAmountCents()) {
            throw new BusinessException(
                    "Refund amount exceeds original payment amount. Original: " + payment.getAmountCents() +
                            ", existing refunds: " + existingRefunds +
                            ", requested: " + request.getRefundAmountCents(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }

        Refund refund = new Refund();
        refund.setPaymentId(paymentId);
        refund.setRefundAmountCents(request.getRefundAmountCents());
        refund.setReason(request.getReason());
        Refund saved = refundRepository.save(refund);

        // Update bill balance
        Bill bill = billRepository.findById(payment.getBillId())
                .orElseThrow(() -> new ResourceNotFoundException("Bill", payment.getBillId()));
        bill.setBalanceCents(bill.getBalanceCents() + request.getRefundAmountCents());
        if (bill.getStatus() == BillStatus.PAID && bill.getBalanceCents() > 0) {
            bill.setStatus(BillStatus.PARTIAL);
        }
        billRepository.save(bill);

        MDC.put("paymentId", paymentId.toString());
        log.info("REFUND_ISSUED amount={}", request.getRefundAmountCents());
        MDC.remove("paymentId");

        auditService.log(actor.getId(), "REFUND_ISSUED", "payments/" + paymentId,
                Map.of("refundCents", request.getRefundAmountCents()));

        return saved;
    }

    @Transactional
    public ShiftHandoverReportDTO processHandover(String shiftId, ShiftHandoverRequest request, User actor) {
        ShiftHandover handover = new ShiftHandover();
        handover.setShiftId(shiftId);
        handover.setSubmittedBy(actor.getId());
        handover.setStatus("RECORDED");
        if (actor.getOrganization() != null) {
            handover.setOrganizationId(actor.getOrganization().getId());
        }
        ShiftHandover savedHandover = shiftHandoverRepository.save(handover);

        // Save submitted totals
        Map<PaymentMethod, Long> submittedByMethod = new HashMap<>();
        if (request.getSubmittedTotals() != null) {
            for (ShiftHandoverRequest.MethodTotal mt : request.getSubmittedTotals()) {
                ShiftHandoverTotal total = new ShiftHandoverTotal();
                total.setHandoverId(savedHandover.getId());
                total.setPaymentMethod(mt.getPaymentMethod());
                total.setTotalAmountCents(mt.getTotalAmountCents());
                shiftHandoverTotalRepository.save(total);
                submittedByMethod.put(mt.getPaymentMethod(), mt.getTotalAmountCents());
            }
        }

        // Get posted A/R for shift
        List<Object[]> postedRows = paymentRepository.sumAmountByMethodForShift(shiftId);
        Map<PaymentMethod, Long> postedByMethod = new HashMap<>();
        for (Object[] row : postedRows) {
            PaymentMethod method = (PaymentMethod) row[0];
            Long amount = ((Number) row[1]).longValue();
            postedByMethod.put(method, amount);
        }

        // Compare totals across all methods
        long totalSubmitted = submittedByMethod.values().stream().mapToLong(Long::longValue).sum();
        long totalPosted = postedByMethod.values().stream().mapToLong(Long::longValue).sum();
        long delta = totalSubmitted - totalPosted;

        boolean discrepancyCreated = false;
        UUID discrepancyCaseId = null;

        if (Math.abs(delta) > 100L) {
            // Find a billing clerk to assign
            List<User> clerks = userRepository.findByRole(Role.BILLING_CLERK);
            User assignee = clerks.isEmpty() ? null : clerks.get(0);

            DiscrepancyCase discCase = new DiscrepancyCase();
            discCase.setHandoverId(savedHandover.getId());
            discCase.setOrganizationId(savedHandover.getOrganizationId());
            discCase.setDeltaCents(delta);
            discCase.setStatus(DiscrepancyStatus.OPEN);
            if (assignee != null) discCase.setAssignedTo(assignee.getId());
            DiscrepancyCase savedCase = discrepancyCaseRepository.save(discCase);
            discrepancyCreated = true;
            discrepancyCaseId = savedCase.getId();

            auditService.log(actor.getId(), "DISCREPANCY_CASE_CREATED",
                    "shift_handovers/" + savedHandover.getId(),
                    Map.of("delta", delta));
        }

        // Build report-grade DTO
        ShiftHandoverReportDTO report = new ShiftHandoverReportDTO();
        report.setHandoverId(savedHandover.getId());
        report.setShiftId(savedHandover.getShiftId());
        report.setStatus(savedHandover.getStatus());

        // Named payment-method totals from submitted data
        report.setTotalCash(submittedByMethod.getOrDefault(PaymentMethod.CASH, 0L));
        report.setTotalCheck(submittedByMethod.getOrDefault(PaymentMethod.CHECK, 0L));
        report.setTotalVoucher(submittedByMethod.getOrDefault(PaymentMethod.VOUCHER, 0L));
        report.setTotalOther(submittedByMethod.getOrDefault(PaymentMethod.OTHER, 0L));

        report.setSubmittedTotal(totalSubmitted);
        report.setExpectedTotal(totalPosted);
        report.setDelta(delta);
        report.setDiscrepancyFlag(discrepancyCreated);
        report.setDiscrepancyCaseId(discrepancyCaseId);

        // Full breakdowns for audit
        Map<String, Long> submittedMap = new LinkedHashMap<>();
        submittedByMethod.forEach((k, v) -> submittedMap.put(k.name(), v));
        report.setSubmittedByMethod(submittedMap);
        Map<String, Long> postedMap = new LinkedHashMap<>();
        postedByMethod.forEach((k, v) -> postedMap.put(k.name(), v));
        report.setPostedByMethod(postedMap);

        return report;
    }

    @Transactional(readOnly = true)
    public List<Payment> findPaymentsForBill(UUID billId) {
        return paymentRepository.findByBillId(billId);
    }

    @Transactional(readOnly = true)
    public List<Refund> findRefundsForPayment(UUID paymentId) {
        return refundRepository.findByPaymentId(paymentId);
    }

    @Transactional(readOnly = true)
    public Page<DiscrepancyCase> findDiscrepanciesPage(DiscrepancyStatus status,
                                                        OffsetDateTime from,
                                                        OffsetDateTime to,
                                                        Pageable pageable,
                                                        User actor) {
        UUID orgId = AuthorizationService.resolveOrgId(actor);
        if (orgId != null) {
            return discrepancyCaseRepository.findWithFiltersAndOrg(orgId, status, from, to, pageable);
        }
        return discrepancyCaseRepository.findWithFilters(status, from, to, pageable);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void notifyOrgUsers(UUID orgId, String type, String title, String body, String entityRef) {
        if (orgId == null || notificationService == null) return;
        try {
            List<User> users = userRepository.findByOrganizationId(orgId);
            for (User u : users) {
                notificationService.createNotification(u.getId(), type, title, body, entityRef);
            }
        } catch (Exception e) {
            log.warn("Failed to send payment event notification: {}", e.getMessage());
        }
    }

    private Bill loadBillWithTenantCheck(UUID billId, User actor) {
        UUID orgId = AuthorizationService.resolveOrgId(actor);
        if (orgId != null) {
            return billRepository.findByIdAndOrganizationId(billId, orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Bill", billId));
        }
        return billRepository.findById(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill", billId));
    }

    private static void requireVersion(Integer requestedVersion, Integer serverVersion,
                                        String entityType, UUID entityId,
                                        Map<String, Object> stateSummary) {
        if (requestedVersion == null || !requestedVersion.equals(serverVersion)) {
            throw new VersionConflictException(entityType, entityId, serverVersion, stateSummary);
        }
    }

    @Transactional
    public DiscrepancyCase resolveDiscrepancy(UUID caseId, com.civicworks.dto.ResolveDiscrepancyRequest request, User actor) {
        UUID orgId = AuthorizationService.resolveOrgId(actor);
        DiscrepancyCase discCase;
        if (orgId != null) {
            discCase = discrepancyCaseRepository.findByIdAndOrganizationId(caseId, orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("DiscrepancyCase", caseId));
        } else {
            discCase = discrepancyCaseRepository.findById(caseId)
                    .orElseThrow(() -> new ResourceNotFoundException("DiscrepancyCase", caseId));
        }
        discCase.setStatus(request.getResolution());
        discCase.setNotes(request.getNotes());
        discCase.setResolvedBy(actor.getId());
        DiscrepancyCase saved = discrepancyCaseRepository.save(discCase);

        auditService.log(actor.getId(), "DISCREPANCY_RESOLVED", "discrepancy_cases/" + caseId,
                Map.of("resolution", request.getResolution().toString()));

        return saved;
    }
}
