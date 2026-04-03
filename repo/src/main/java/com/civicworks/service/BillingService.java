package com.civicworks.service;

import com.civicworks.domain.entity.*;
import com.civicworks.domain.enums.*;
import com.civicworks.dto.ApplyDiscountRequest;
import com.civicworks.dto.CreateBillingRunRequest;
import com.civicworks.dto.CreateFeeItemRequest;
import com.civicworks.exception.BusinessException;
import com.civicworks.exception.ResourceNotFoundException;
import com.civicworks.exception.VersionConflictException;
import com.civicworks.repository.*;
import com.civicworks.scheduler.QuartzSchedulerConfig;
import com.civicworks.domain.enums.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final FeeItemRepository feeItemRepository;
    private final BillingRunRepository billingRunRepository;
    private final BillRepository billRepository;
    private final BillDiscountRepository billDiscountRepository;
    private final LateFeeEventRepository lateFeeEventRepository;
    private final AccountRepository accountRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final AuditService auditService;
    private final QuartzSchedulerConfig quartzSchedulerConfig;
    private final NotificationService notificationService;
    private final UserRepository userRepository;

    public BillingService(FeeItemRepository feeItemRepository,
                          BillingRunRepository billingRunRepository,
                          BillRepository billRepository,
                          BillDiscountRepository billDiscountRepository,
                          LateFeeEventRepository lateFeeEventRepository,
                          AccountRepository accountRepository,
                          UsageRecordRepository usageRecordRepository,
                          AuditService auditService,
                          QuartzSchedulerConfig quartzSchedulerConfig,
                          NotificationService notificationService,
                          UserRepository userRepository) {
        this.feeItemRepository = feeItemRepository;
        this.billingRunRepository = billingRunRepository;
        this.billRepository = billRepository;
        this.billDiscountRepository = billDiscountRepository;
        this.lateFeeEventRepository = lateFeeEventRepository;
        this.accountRepository = accountRepository;
        this.usageRecordRepository = usageRecordRepository;
        this.auditService = auditService;
        this.quartzSchedulerConfig = quartzSchedulerConfig;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
    }

    @Transactional
    public FeeItem createFeeItem(CreateFeeItemRequest request, User actor) {
        feeItemRepository.findByCode(request.getCode()).ifPresent(f -> {
            throw new BusinessException("Fee item with code already exists: " + request.getCode(), HttpStatus.CONFLICT);
        });
        FeeItem item = new FeeItem();
        item.setCode(request.getCode());
        item.setCalculationType(request.getCalculationType());
        item.setRateCents(request.getRateCents());
        item.setTaxableFlag(request.isTaxableFlag());
        if (actor.getOrganization() != null) {
            item.setOrganizationId(actor.getOrganization().getId());
        }
        return feeItemRepository.save(item);
    }

    @Transactional
    public BillingRun createBillingRun(CreateBillingRunRequest request, User actor, String idempotencyKeyHeader) {
        String idempKey = request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()
                ? request.getIdempotencyKey()
                : (idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()
                        ? idempotencyKeyHeader
                        : "billing-run-" + request.getCycleDate() + "-" + request.getBillingCycle());

        // Idempotency check
        billingRunRepository.findByIdempotencyKey(idempKey).ifPresent(run -> {
            throw new BusinessException("Billing run already exists with idempotency key: " + idempKey, HttpStatus.CONFLICT);
        });

        UUID orgId = AuthorizationService.resolveOrgId(actor);
        BillingRun run = new BillingRun();
        run.setCycleDate(request.getCycleDate());
        run.setBillingCycle(request.getBillingCycle());
        run.setStatus(BillingRunStatus.PENDING);
        run.setIdempotencyKey(idempKey);
        run.setRequestedBy(actor.getUsername());
        run.setOrganizationId(orgId);

        BillingRun saved = billingRunRepository.save(run);

        log.info("BILLING_RUN_CREATED billingRunId={} cycleDate={} cycle={}", saved.getId(), request.getCycleDate(), request.getBillingCycle());

        auditService.log(actor.getId(), "BILLING_RUN_CREATED", "billing_runs/" + saved.getId(),
                Map.of("cycleDate", request.getCycleDate().toString()));

        try {
            quartzSchedulerConfig.scheduleBillingRunJob(saved.getId(), request.getCycleDate());
        } catch (Exception e) {
            log.error("Failed to schedule billing run job for {}: {}", saved.getId(), e.getMessage(), e);
        }

        return saved;
    }

    @Transactional
    public void executeBillingRun(UUID billingRunId) {
        BillingRun run = billingRunRepository.findById(billingRunId)
                .orElseThrow(() -> new ResourceNotFoundException("BillingRun", billingRunId));

        // Idempotency: if already succeeded, do nothing
        if (run.getStatus() == BillingRunStatus.SUCCESS) {
            log.info("BILLING_RUN_ALREADY_COMPLETE billingRunId={}", billingRunId);
            return;
        }

        // Idempotency: if bills were partially created, wipe and restart only on FAILED
        if (run.getStatus() == BillingRunStatus.RUNNING) {
            log.warn("BILLING_RUN_INTERRUPTED billingRunId={} — previous run was in RUNNING state, re-executing", billingRunId);
        }

        run.setStatus(BillingRunStatus.RUNNING);
        billingRunRepository.save(run);
        log.info("BILLING_RUN_EXECUTING billingRunId={}", billingRunId);

        try {
            List<Account> accounts = run.getOrganizationId() != null
                    ? accountRepository.findByOrganizationId(run.getOrganizationId())
                    : accountRepository.findAll();
            // Pre-load existing bills for this run to skip already-generated accounts
            java.util.Set<UUID> alreadyBilled = billRepository.findByBillingRunId(billingRunId)
                    .stream().map(Bill::getAccountId).collect(java.util.stream.Collectors.toSet());

            int created = 0;
            int skipped = 0;
            for (Account account : accounts) {
                if (alreadyBilled.contains(account.getId())) {
                    skipped++;
                    continue;
                }

                long amountCents = computeBillAmount(account.getOrganizationId(), account.getId(), run.getCycleDate());
                // Skip zero-amount bills (no fee items configured for this org)
                if (amountCents == 0L) {
                    continue;
                }

                Bill bill = new Bill();
                bill.setAccountId(account.getId());
                bill.setBillingRunId(billingRunId);
                bill.setCycleDate(run.getCycleDate());
                bill.setDueDate(run.getCycleDate().plusDays(30));
                bill.setAmountCents(amountCents);
                bill.setBalanceCents(amountCents);
                bill.setStatus(BillStatus.OPEN);
                bill.setOrganizationId(account.getOrganizationId());
                Bill savedBill = billRepository.save(bill);
                created++;

                notifyOrgUsers(account.getOrganizationId(), "BILL_CREATED",
                        "New bill generated",
                        "A new bill of " + amountCents + " cents has been generated for account "
                                + account.getId() + ". Due date: " + bill.getDueDate() + ".",
                        "bills/" + savedBill.getId());
            }

            run.setStatus(BillingRunStatus.SUCCESS);
            billingRunRepository.save(run);
            log.info("BILLING_RUN_COMPLETE billingRunId={} created={} skipped={}", billingRunId, created, skipped);

            auditService.log(null, "BILLING_RUN_COMPLETE", "billing_runs/" + billingRunId,
                    Map.of("created", created, "totalAccounts", accounts.size()));
        } catch (Exception e) {
            run.setStatus(BillingRunStatus.FAILED);
            billingRunRepository.save(run);
            log.error("BILLING_RUN_FAILED billingRunId={} error={}", billingRunId, e.getMessage());
            throw new BusinessException("Billing run execution failed: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Calculates the total billable amount in cents for an account in the given
     * organisation by summing all applicable fee items:
     * <ul>
     *   <li>FLAT    — applied once at face value (rateCents)</li>
     *   <li>PER_UNIT — rateCents × quantity from usage_records for this account
     *       and billing period; quantity defaults to 0 when no record exists.</li>
     *   <li>METERED — same as PER_UNIT; quantity from usage_records.</li>
     * </ul>
     * Results are deterministic and auditable: the same usage records always
     * produce the same bill amount.
     */
    private long computeBillAmount(UUID organizationId, UUID accountId, LocalDate cycleDate) {
        if (organizationId == null) return 0L;
        List<FeeItem> items = feeItemRepository.findByOrganizationId(organizationId);
        long total = 0L;
        for (FeeItem item : items) {
            switch (item.getCalculationType()) {
                case FLAT:
                    total += item.getRateCents();
                    break;
                case PER_UNIT:
                case METERED: {
                    long qty = usageRecordRepository.sumQuantity(accountId, item.getId(), cycleDate);
                    total += item.getRateCents() * qty;
                    break;
                }
            }
        }
        return total;
    }

    @Transactional
    public Bill applyLateFee(UUID billId, Integer entityVersion, User actor) {
        Bill bill = loadBillWithTenantCheck(billId, actor);

        requireVersion(entityVersion, bill.getVersion(), "Bill", billId,
                Map.of("status", bill.getStatus().name(), "balanceCents", bill.getBalanceCents()));

        if (bill.getStatus() == BillStatus.PAID || bill.getStatus() == BillStatus.CANCELLED) {
            throw new BusinessException("Cannot apply late fee to PAID or CANCELLED bill", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        if (lateFeeEventRepository.existsByBillId(billId)) {
            throw new BusinessException("Late fee already applied to this bill", HttpStatus.CONFLICT);
        }

        // Check grace period: due_date + 10 days
        LocalDate gracePeriodEnd = bill.getDueDate().plusDays(10);
        if (LocalDate.now().isBefore(gracePeriodEnd)) {
            throw new BusinessException("Bill is still within grace period until " + gracePeriodEnd, HttpStatus.UNPROCESSABLE_ENTITY);
        }

        // Calculate 5% of balance, capped at 5000 cents ($50)
        long feeRaw = (long) (bill.getBalanceCents() * 0.05);
        long lateFee = Math.min(feeRaw, 5000L);

        LateFeeEvent event = new LateFeeEvent();
        event.setBillId(billId);
        event.setLateFeeCents(lateFee);
        lateFeeEventRepository.save(event);

        bill.setBalanceCents(bill.getBalanceCents() + lateFee);
        if (bill.getStatus() == BillStatus.OPEN) {
            bill.setStatus(BillStatus.OVERDUE);
        }
        Bill saved = billRepository.save(bill);

        log.info("LATE_FEE_APPLIED billId={} lateFeeCents={}", billId, lateFee);

        auditService.log(actor != null ? actor.getId() : null, "LATE_FEE_APPLIED",
                "bills/" + billId, Map.of("lateFeeCents", lateFee));

        notifyOrgUsers(bill.getOrganizationId(), "LATE_FEE_APPLIED",
                "Late fee applied to bill",
                "A late fee of " + lateFee + " cents has been applied to bill " + billId + ".",
                "bills/" + billId);

        return saved;
    }

    @Transactional
    public Bill applyDiscount(UUID billId, ApplyDiscountRequest request, User actor) {
        Bill bill = loadBillWithTenantCheck(billId, actor);

        requireVersion(request.getEntityVersion(), bill.getVersion(), "Bill", billId,
                Map.of("status", bill.getStatus().name(), "balanceCents", bill.getBalanceCents()));

        if (billDiscountRepository.existsByBillId(billId)) {
            throw new BusinessException("A discount has already been applied to this bill", HttpStatus.UNPROCESSABLE_ENTITY, "DISCOUNT_ALREADY_APPLIED");
        }

        long discountAmount;
        if (request.getDiscountType() == DiscountType.PERCENTAGE) {
            // value is in basis points (100 bp = 1%)
            discountAmount = (bill.getBalanceCents() * request.getValueBasisPointsOrCents()) / 10000L;
        } else {
            discountAmount = request.getValueBasisPointsOrCents();
        }

        long newBalance = Math.max(0L, bill.getBalanceCents() - discountAmount);

        BillDiscount discount = new BillDiscount();
        discount.setBillId(billId);
        discount.setDiscountType(request.getDiscountType());
        discount.setValueBasisPointsOrCents(request.getValueBasisPointsOrCents());
        billDiscountRepository.save(discount);

        bill.setBalanceCents(newBalance);
        Bill saved = billRepository.save(bill);

        log.info("DISCOUNT_APPLIED billId={} discountType={} amount={}", billId, request.getDiscountType(), discountAmount);

        auditService.log(actor.getId(), "DISCOUNT_APPLIED", "bills/" + billId,
                Map.of("discountType", request.getDiscountType().toString(), "amount", discountAmount));

        notifyOrgUsers(bill.getOrganizationId(), "DISCOUNT_APPLIED",
                "Discount applied to bill",
                "A " + request.getDiscountType() + " discount of " + discountAmount
                        + " cents has been applied to bill " + billId + ".",
                "bills/" + billId);

        return saved;
    }

    @Transactional(readOnly = true)
    public List<Bill> findAllBills() {
        return billRepository.findAll();
    }

    /**
     * Returns a paginated list of bills scoped to the caller's organisation.
     *
     * @param orgId     caller's organisation id; {@code null} for SYSTEM_ADMIN (sees all)
     * @param accountId optional filter by account
     * @param status    optional filter by bill status string
     */
    @Transactional(readOnly = true)
    public Page<Bill> findBillsPage(UUID orgId, UUID accountId, String status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, size));
        return billRepository.findWithFiltersAndOrg(orgId, accountId, status, pageable);
    }

    /**
     * Retrieves a bill by id, scoped to the caller's organisation.
     * Returns 404 for both non-existent and cross-organisation bills.
     *
     * @param billId bill id
     * @param orgId  caller's org id; {@code null} for SYSTEM_ADMIN (no tenant filter)
     */
    @Transactional(readOnly = true)
    public Bill findBillById(UUID billId, UUID orgId) {
        if (orgId != null) {
            return billRepository.findByIdAndOrganizationId(billId, orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Bill", billId));
        }
        return billRepository.findById(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill", billId));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Loads a bill, enforcing tenant isolation.  SYSTEM_ADMIN (or scheduler
     * calls with a null actor) see all bills; other roles are restricted to
     * bills belonging to their organisation.
     */
    private Bill loadBillWithTenantCheck(UUID billId, User actor) {
        UUID orgId = AuthorizationService.resolveOrgId(actor);
        if (orgId != null) {
            return billRepository.findByIdAndOrganizationId(billId, orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Bill", billId));
        }
        return billRepository.findById(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill", billId));
    }

    private void notifyOrgUsers(UUID orgId, String type, String title, String body, String entityRef) {
        if (orgId == null || notificationService == null) return;
        try {
            List<User> users = userRepository.findByOrganizationId(orgId);
            for (User u : users) {
                notificationService.createNotification(u.getId(), type, title, body, entityRef);
            }
        } catch (Exception e) {
            log.warn("Failed to send billing event notification: {}", e.getMessage());
        }
    }

    private static void requireVersion(Integer requestedVersion, Integer serverVersion,
                                        String entityType, UUID entityId,
                                        Map<String, Object> stateSummary) {
        if (requestedVersion == null || !requestedVersion.equals(serverVersion)) {
            throw new VersionConflictException(entityType, entityId, serverVersion, stateSummary);
        }
    }
}
