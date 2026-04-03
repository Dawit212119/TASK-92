package com.civicworks.controller;

import com.civicworks.config.AuthUtils;
import com.civicworks.config.IdempotencyGuard;
import com.civicworks.domain.entity.*;
import com.civicworks.service.AuthorizationService;
import com.civicworks.domain.enums.DiscrepancyStatus;
import org.springframework.format.annotation.DateTimeFormat;
import com.civicworks.dto.*;
import com.civicworks.dto.ShiftHandoverSummary;
import com.civicworks.service.BillingService;
import com.civicworks.service.PaymentService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private static final Logger log = LoggerFactory.getLogger(BillingController.class);

    private final BillingService billingService;
    private final PaymentService paymentService;
    private final AuthUtils authUtils;
    private final IdempotencyGuard idempotencyGuard;

    public BillingController(BillingService billingService,
                             PaymentService paymentService,
                             AuthUtils authUtils,
                             IdempotencyGuard idempotencyGuard) {
        this.billingService = billingService;
        this.paymentService = paymentService;
        this.authUtils = authUtils;
        this.idempotencyGuard = idempotencyGuard;
    }

    @PostMapping("/fee-items")
    @PreAuthorize("hasRole('BILLING_CLERK') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<FeeItem> createFeeItem(
            @Valid @RequestBody CreateFeeItemRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return idempotencyGuard.execute(idempotencyKey, actor.getId(), "CREATE_FEE_ITEM",
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(billingService.createFeeItem(request, actor)));
    }

    @PostMapping("/billing-runs")
    @PreAuthorize("hasRole('BILLING_CLERK') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<BillingRun> createBillingRun(
            @Valid @RequestBody CreateBillingRunRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return idempotencyGuard.execute(idempotencyKey, actor.getId(), "CREATE_BILLING_RUN", () -> {
            BillingRun run = billingService.createBillingRun(request, actor, idempotencyKey);
            MDC.put("billingRunId", run.getId().toString());
            log.info("BILLING_RUN_CREATED_CONTROLLER billingRunId={} actor={}", run.getId(), actor.getUsername());
            MDC.remove("billingRunId");
            return ResponseEntity.status(HttpStatus.CREATED).body(run);
        });
    }

    @GetMapping("/bills")
    @PreAuthorize("hasRole('BILLING_CLERK') or hasRole('AUDITOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, Object>> findAllBills(
            @RequestParam(required = false) UUID account_id,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        UUID orgId = AuthorizationService.resolveOrgId(actor);
        Page<Bill> result = billingService.findBillsPage(orgId, account_id, status, page, size);
        return ResponseEntity.ok(Map.of(
                "data", result.getContent(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "total", result.getTotalElements()
        ));
    }

    @PostMapping("/bills/{billId}/late-fee/apply")
    @PreAuthorize("hasRole('BILLING_CLERK') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Bill> applyLateFee(
            @PathVariable UUID billId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Entity-Version") Integer entityVersion,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return idempotencyGuard.execute(idempotencyKey, actor.getId(), "APPLY_LATE_FEE",
                () -> ResponseEntity.ok(billingService.applyLateFee(billId, entityVersion, actor)));
    }

    @PostMapping("/bills/{billId}/discount")
    @PreAuthorize("hasRole('BILLING_CLERK') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Bill> applyDiscount(
            @PathVariable UUID billId,
            @Valid @RequestBody ApplyDiscountRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return idempotencyGuard.execute(idempotencyKey, actor.getId(), "APPLY_DISCOUNT",
                () -> ResponseEntity.ok(billingService.applyDiscount(billId, request, actor)));
    }

    @PostMapping("/bills/{billId}/settlements")
    @PreAuthorize("hasRole('BILLING_CLERK') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Settlement> createSettlement(
            @PathVariable UUID billId,
            @Valid @RequestBody CreateSettlementRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        MDC.put("billId", billId.toString());
        log.info("SETTLEMENT_CREATE_REQUEST billId={} mode={} actor={}", billId, request.getSettlementMode(), actor.getUsername());
        MDC.remove("billId");
        return idempotencyGuard.execute(idempotencyKey, actor.getId(), "CREATE_SETTLEMENT",
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(paymentService.createSettlement(billId, request, actor)));
    }

    @PostMapping("/payments/{paymentId}/refunds")
    @PreAuthorize("hasRole('BILLING_CLERK') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Refund> createRefund(
            @PathVariable UUID paymentId,
            @Valid @RequestBody CreateRefundRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return idempotencyGuard.execute(idempotencyKey, actor.getId(), "CREATE_REFUND",
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(paymentService.createRefund(paymentId, request, actor)));
    }

    @GetMapping("/bills/{billId}/ledger")
    @PreAuthorize("hasRole('BILLING_CLERK') or hasRole('AUDITOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, Object>> getBillLedger(@PathVariable UUID billId,
                                                              Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        UUID orgId = AuthorizationService.resolveOrgId(actor);
        Bill bill = billingService.findBillById(billId, orgId);
        List<Payment> payments = paymentService.findPaymentsForBill(billId);
        return ResponseEntity.ok(Map.of("bill", bill, "payments", payments));
    }

    @PostMapping("/shifts/{shiftId}/handover")
    @PreAuthorize("hasRole('BILLING_CLERK') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<com.civicworks.dto.ShiftHandoverReportDTO> processHandover(
            @PathVariable String shiftId,
            @Valid @RequestBody ShiftHandoverRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return idempotencyGuard.execute(idempotencyKey, actor.getId(), "PROCESS_HANDOVER",
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(paymentService.processHandover(shiftId, request, actor)));
    }

    @GetMapping("/discrepancies")
    @PreAuthorize("hasRole('BILLING_CLERK') or hasRole('AUDITOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, Object>> findDiscrepancies(
            @RequestParam(required = false) DiscrepancyStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        Page<DiscrepancyCase> result = paymentService.findDiscrepanciesPage(
                status, from, to, PageRequest.of(Math.max(0, page), Math.min(100, size)), actor);
        return ResponseEntity.ok(Map.of(
                "data", result.getContent(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "total", result.getTotalElements()
        ));
    }

    @PostMapping("/discrepancies/{caseId}/resolve")
    @PreAuthorize("hasRole('BILLING_CLERK') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<DiscrepancyCase> resolveDiscrepancy(
            @PathVariable UUID caseId,
            @Valid @RequestBody ResolveDiscrepancyRequest request,
            Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        return ResponseEntity.ok(paymentService.resolveDiscrepancy(caseId, request, actor));
    }
}
