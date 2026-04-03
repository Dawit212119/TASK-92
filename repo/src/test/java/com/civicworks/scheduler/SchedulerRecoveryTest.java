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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.quartz.SchedulerException;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests proving startup-recovery logic in QuartzStartupListener:
 * - overdue SCHEDULED content items are published exactly once
 * - stuck billing runs with past cycleDate are re-executed
 * - future-dated billing runs are skipped
 * - single publish failure does not prevent remaining items from being processed
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerRecoveryTest {

    @Mock QuartzSchedulerConfig quartzSchedulerConfig;
    @Mock ContentItemRepository contentItemRepository;
    @Mock ContentService contentService;
    @Mock UserRepository userRepository;
    @Mock BillingRunRepository billingRunRepository;
    @Mock BillingService billingService;

    private QuartzStartupListener listener;

    @BeforeEach
    void setUp() throws SchedulerException {
        listener = new QuartzStartupListener(
                quartzSchedulerConfig, contentItemRepository, contentService,
                userRepository, billingRunRepository, billingService);

        // Default stubs: no overdue items, no stuck runs
        when(contentItemRepository.findByStateAndScheduledAtBefore(any(), any())).thenReturn(List.of());
        when(billingRunRepository.findByStatusIn(any())).thenReturn(List.of());
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());
        doNothing().when(quartzSchedulerConfig).scheduleRecurringJobs();
    }

    // -----------------------------------------------------------------------
    // Content item recovery
    // -----------------------------------------------------------------------

    @Test
    void overdueScheduledItem_isPublishedOnStartup() throws Exception {
        UUID itemId = UUID.randomUUID();
        ContentItem item = scheduledItem(itemId, 2);

        when(contentItemRepository.findByStateAndScheduledAtBefore(eq(ContentState.SCHEDULED), any()))
                .thenReturn(List.of(item));

        listener.onApplicationReady();

        verify(contentService).publishItem(eq(itemId), eq(2), any(User.class));
    }

    @Test
    void noOverdueItems_publishNeverCalled() {
        when(contentItemRepository.findByStateAndScheduledAtBefore(any(), any())).thenReturn(List.of());

        listener.onApplicationReady();

        verify(contentService, never()).publishItem(any(), any(), any());
    }

    @Test
    void publishFailsForOneItem_remainingItemsStillProcessed() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        when(contentItemRepository.findByStateAndScheduledAtBefore(any(), any()))
                .thenReturn(List.of(scheduledItem(id1, 1), scheduledItem(id2, 3)));

        doThrow(new RuntimeException("publish error")).when(contentService)
                .publishItem(eq(id1), any(), any());

        listener.onApplicationReady();

        verify(contentService).publishItem(eq(id1), eq(1), any());
        verify(contentService).publishItem(eq(id2), eq(3), any());
    }

    @Test
    void adminUserFound_usedAsSystemActor() throws Exception {
        User admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setUsername("admin");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        UUID itemId = UUID.randomUUID();
        when(contentItemRepository.findByStateAndScheduledAtBefore(any(), any()))
                .thenReturn(List.of(scheduledItem(itemId, 1)));

        listener.onApplicationReady();

        verify(contentService).publishItem(eq(itemId), eq(1), eq(admin));
    }

    // -----------------------------------------------------------------------
    // Billing run recovery
    // -----------------------------------------------------------------------

    @Test
    void stuckBillingRun_pastCycleDate_isReExecuted() {
        BillingRun run = billingRun(BillingRunStatus.FAILED, LocalDate.now().minusDays(1));

        when(billingRunRepository.findByStatusIn(any())).thenReturn(List.of(run));

        listener.onApplicationReady();

        verify(billingService).executeBillingRun(run.getId());
    }

    @Test
    void stuckBillingRun_futureCycleDate_isSkipped() {
        BillingRun run = billingRun(BillingRunStatus.PENDING, LocalDate.now().plusDays(3));

        when(billingRunRepository.findByStatusIn(any())).thenReturn(List.of(run));

        listener.onApplicationReady();

        verify(billingService, never()).executeBillingRun(any());
    }

    @Test
    void stuckBillingRun_todayCycleDate_isReExecuted() {
        BillingRun run = billingRun(BillingRunStatus.RUNNING, LocalDate.now());

        when(billingRunRepository.findByStatusIn(any())).thenReturn(List.of(run));

        listener.onApplicationReady();

        verify(billingService).executeBillingRun(run.getId());
    }

    @Test
    void stuckRunFails_recoveryExceptionSwallowed_otherRunsStillProcessed() {
        BillingRun run1 = billingRun(BillingRunStatus.FAILED, LocalDate.now().minusDays(1));
        BillingRun run2 = billingRun(BillingRunStatus.FAILED, LocalDate.now().minusDays(2));

        when(billingRunRepository.findByStatusIn(any())).thenReturn(List.of(run1, run2));
        doThrow(new RuntimeException("db error")).when(billingService).executeBillingRun(run1.getId());

        listener.onApplicationReady();

        verify(billingService).executeBillingRun(run1.getId());
        verify(billingService).executeBillingRun(run2.getId());
    }

    @Test
    void recurringJobsRegisteredEvenWhenNoRecoveryNeeded() throws Exception {
        listener.onApplicationReady();

        verify(quartzSchedulerConfig).scheduleRecurringJobs();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ContentItem scheduledItem(UUID id, int version) {
        ContentItem item = new ContentItem();
        item.setId(id);
        item.setState(ContentState.SCHEDULED);
        setField(item, "version", version);
        return item;
    }

    private static BillingRun billingRun(BillingRunStatus status, LocalDate cycleDate) {
        BillingRun run = new BillingRun();
        run.setId(UUID.randomUUID());
        run.setStatus(status);
        run.setCycleDate(cycleDate);
        return run;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Could not set field " + fieldName + " on " + target.getClass().getSimpleName(), e);
        }
    }
}
