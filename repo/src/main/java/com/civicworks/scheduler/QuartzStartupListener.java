package com.civicworks.scheduler;

import com.civicworks.domain.entity.BillingRun;
import com.civicworks.domain.entity.ContentItem;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.BillingRunStatus;
import com.civicworks.domain.enums.ContentState;
import com.civicworks.repository.BillingRunRepository;
import com.civicworks.repository.ContentItemRepository;
import com.civicworks.repository.UserRepository;
import com.civicworks.service.BillingService;
import com.civicworks.service.ContentService;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class QuartzStartupListener {

    private static final Logger log = LoggerFactory.getLogger(QuartzStartupListener.class);

    private final QuartzSchedulerConfig quartzSchedulerConfig;
    private final ContentItemRepository contentItemRepository;
    private final ContentService contentService;
    private final UserRepository userRepository;
    private final BillingRunRepository billingRunRepository;
    private final BillingService billingService;

    public QuartzStartupListener(QuartzSchedulerConfig quartzSchedulerConfig,
                                  ContentItemRepository contentItemRepository,
                                  ContentService contentService,
                                  UserRepository userRepository,
                                  BillingRunRepository billingRunRepository,
                                  BillingService billingService) {
        this.quartzSchedulerConfig = quartzSchedulerConfig;
        this.contentItemRepository = contentItemRepository;
        this.contentService = contentService;
        this.userRepository = userRepository;
        this.billingRunRepository = billingRunRepository;
        this.billingService = billingService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        registerRecurringJobs();
        recoverOverdueContentItems();
        recoverStuckBillingRuns();
    }

    private void registerRecurringJobs() {
        try {
            quartzSchedulerConfig.scheduleRecurringJobs();
            log.info("SCHEDULER_STARTUP: Recurring Quartz jobs registered successfully.");
        } catch (SchedulerException e) {
            log.error("SCHEDULER_STARTUP: Failed to register recurring Quartz jobs: {}", e.getMessage(), e);
        }
    }

    /**
     * Publishes any content items that were in SCHEDULED state with a scheduledAt
     * time that has already passed. This recovers jobs that were lost during a
     * restart (e.g. the Quartz trigger fired but the app was down).
     */
    private void recoverOverdueContentItems() {
        OffsetDateTime now = OffsetDateTime.now();
        List<ContentItem> overdue = contentItemRepository.findByStateAndScheduledAtBefore(ContentState.SCHEDULED, now);
        if (overdue.isEmpty()) {
            log.info("SCHEDULER_RECOVERY: No overdue SCHEDULED content items found.");
            return;
        }

        log.info("SCHEDULER_RECOVERY: Found {} overdue SCHEDULED content item(s) — publishing.", overdue.size());

        User systemActor = userRepository.findByUsername("admin")
                .orElseGet(() -> {
                    User dummy = new User();
                    dummy.setId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
                    return dummy;
                });

        for (ContentItem item : overdue) {
            try {
                contentService.publishItem(item.getId(), item.getVersion(), systemActor);
                log.info("SCHEDULER_RECOVERY: Published contentItemId={}", item.getId());
            } catch (Exception e) {
                log.warn("SCHEDULER_RECOVERY: Failed to publish contentItemId={}: {}", item.getId(), e.getMessage());
            }
        }
    }

    /**
     * Re-executes billing runs that are stuck in PENDING, RUNNING, or FAILED state
     * whose cycleDate is today or in the past. RUNNING means the app died mid-run;
     * executeBillingRun handles idempotency by skipping already-generated bills.
     */
    private void recoverStuckBillingRuns() {
        List<BillingRun> stuck = billingRunRepository.findByStatusIn(
                List.of(BillingRunStatus.PENDING, BillingRunStatus.RUNNING, BillingRunStatus.FAILED));
        if (stuck.isEmpty()) {
            log.info("SCHEDULER_RECOVERY: No stuck billing runs found.");
            return;
        }

        log.info("SCHEDULER_RECOVERY: Found {} stuck billing run(s) — recovering.", stuck.size());

        for (BillingRun run : stuck) {
            if (run.getCycleDate().isAfter(LocalDate.now())) {
                log.info("SCHEDULER_RECOVERY: Billing run {} has future cycleDate {}, skipping.",
                        run.getId(), run.getCycleDate());
                continue;
            }
            try {
                log.info("SCHEDULER_RECOVERY: Re-executing billing run {} (status={})",
                        run.getId(), run.getStatus());
                billingService.executeBillingRun(run.getId());
                log.info("SCHEDULER_RECOVERY: Billing run {} recovered successfully.", run.getId());
            } catch (Exception e) {
                log.warn("SCHEDULER_RECOVERY: Failed to recover billing run {}: {}", run.getId(), e.getMessage());
            }
        }
    }
}
