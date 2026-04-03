package com.civicworks.scheduler;

import com.civicworks.service.BillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.quartz.*;
import org.quartz.impl.JobDetailImpl;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests proving retry-with-backoff behaviour in BillingRunJob:
 * - successful execution: no retry scheduled
 * - first failure: retry trigger with retryCount=1 is scheduled
 * - failure at max retries: no further retry trigger scheduled
 * - missing billingRunId in data map: returns without error, no service call
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingRunJobTest {

    @Mock BillingService billingService;
    @Mock JobExecutionContext context;
    @Mock Scheduler scheduler;

    private BillingRunJob job;
    private final UUID billingRunId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        job = new BillingRunJob();
        injectField(job, "billingService", billingService);

        JobDetail jobDetail = JobBuilder.newJob(BillingRunJob.class)
                .withIdentity("billingRunJob-" + billingRunId, "billing")
                .build();
        when(context.getJobDetail()).thenReturn(jobDetail);
        when(context.getScheduler()).thenReturn(scheduler);
    }

    private JobDataMap dataMap(int retryCount) {
        JobDataMap m = new JobDataMap();
        m.put("billingRunId", billingRunId.toString());
        m.put("retryCount", retryCount);
        return m;
    }

    // -----------------------------------------------------------------------
    // Success path
    // -----------------------------------------------------------------------

    @Test
    void execute_success_noRetryScheduled() throws Exception {
        when(context.getMergedJobDataMap()).thenReturn(dataMap(0));

        assertThatCode(() -> job.execute(context)).doesNotThrowAnyException();

        verify(billingService).executeBillingRun(billingRunId);
        verify(scheduler, never()).scheduleJob(any(Trigger.class));
    }

    // -----------------------------------------------------------------------
    // Retry scheduling
    // -----------------------------------------------------------------------

    @Test
    void execute_firstFailure_schedulesRetryWithIncrementedCount() throws Exception {
        when(context.getMergedJobDataMap()).thenReturn(dataMap(0));
        doThrow(new RuntimeException("transient error")).when(billingService).executeBillingRun(any());

        assertThatThrownBy(() -> job.execute(context))
                .isInstanceOf(JobExecutionException.class)
                .cause().hasMessageContaining("transient error");

        ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).scheduleJob(triggerCaptor.capture());
        Trigger scheduled = triggerCaptor.getValue();

        assertThat(scheduled.getJobDataMap().getIntValue("retryCount")).isEqualTo(1);
        assertThat(scheduled.getKey().getName()).contains("billingRunRetry");
    }

    @Test
    void execute_secondFailure_schedulesRetryWithCount2() throws Exception {
        when(context.getMergedJobDataMap()).thenReturn(dataMap(1));
        doThrow(new RuntimeException("error")).when(billingService).executeBillingRun(any());

        assertThatThrownBy(() -> job.execute(context)).isInstanceOf(JobExecutionException.class);

        ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).scheduleJob(triggerCaptor.capture());
        assertThat(triggerCaptor.getValue().getJobDataMap().getIntValue("retryCount")).isEqualTo(2);
    }

    @Test
    void execute_atMaxRetries_noRetryScheduled() throws Exception {
        when(context.getMergedJobDataMap()).thenReturn(dataMap(3)); // MAX_RETRIES = 3
        doThrow(new RuntimeException("error")).when(billingService).executeBillingRun(any());

        assertThatThrownBy(() -> job.execute(context)).isInstanceOf(JobExecutionException.class);

        verify(scheduler, never()).scheduleJob(any(Trigger.class));
    }

    @Test
    void execute_retryTrigger_firesInTheFuture() throws Exception {
        when(context.getMergedJobDataMap()).thenReturn(dataMap(0));
        doThrow(new RuntimeException("error")).when(billingService).executeBillingRun(any());

        long before = System.currentTimeMillis();
        assertThatThrownBy(() -> job.execute(context)).isInstanceOf(JobExecutionException.class);

        ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).scheduleJob(triggerCaptor.capture());
        // Retry 1 delay = 2^0 * 5 = 5 minutes from now
        assertThat(triggerCaptor.getValue().getStartTime().getTime())
                .isGreaterThan(before + 4 * 60_000); // at least 4 minutes in the future
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    void execute_missingBillingRunId_returnsNormally() throws Exception {
        when(context.getMergedJobDataMap()).thenReturn(new JobDataMap());

        assertThatCode(() -> job.execute(context)).doesNotThrowAnyException();

        verify(billingService, never()).executeBillingRun(any());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void injectField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
