package com.civicworks.scheduler;

import com.civicworks.service.BillingService;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@DisallowConcurrentExecution
@PersistJobDataAfterExecution
public class BillingRunJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(BillingRunJob.class);
    private static final int MAX_RETRIES = 3;

    @Autowired
    private BillingService billingService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String billingRunIdStr = context.getMergedJobDataMap().getString("billingRunId");
        if (billingRunIdStr == null) {
            log.error("BillingRunJob: billingRunId not found in job data map");
            return;
        }

        int retryCount = context.getMergedJobDataMap().getIntValue("retryCount");
        UUID billingRunId = UUID.fromString(billingRunIdStr);
        log.info("BillingRunJob executing billingRunId={} attempt={}", billingRunId, retryCount + 1);

        try {
            billingService.executeBillingRun(billingRunId);
            log.info("BillingRunJob completed successfully billingRunId={}", billingRunId);
        } catch (Exception e) {
            log.error("BillingRunJob failed (attempt {}/{}) billingRunId={}: {}",
                    retryCount + 1, MAX_RETRIES, billingRunId, e.getMessage(), e);

            if (retryCount < MAX_RETRIES) {
                scheduleRetry(context, billingRunIdStr, retryCount);
            } else {
                log.error("BillingRunJob exhausted all retries for billingRunId={}", billingRunId);
            }

            throw new JobExecutionException("BillingRunJob failed: " + e.getMessage(), e, false);
        }
    }

    private void scheduleRetry(JobExecutionContext context, String billingRunIdStr, int currentRetry) {
        int nextRetry = currentRetry + 1;
        long delayMinutes = (long) Math.pow(2, currentRetry) * 5; // 5, 10, 20 minutes
        log.warn("BillingRunJob: scheduling retry {}/{} for billingRunId={} in {} minutes",
                nextRetry, MAX_RETRIES, billingRunIdStr, delayMinutes);

        JobDataMap retryData = new JobDataMap();
        retryData.put("billingRunId", billingRunIdStr);
        retryData.put("retryCount", nextRetry);

        Trigger retryTrigger = TriggerBuilder.newTrigger()
                .withIdentity("billingRunRetry-" + billingRunIdStr + "-" + nextRetry, "billing")
                .forJob(context.getJobDetail().getKey())
                .usingJobData(retryData)
                .startAt(new java.util.Date(System.currentTimeMillis() + delayMinutes * 60_000L))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                        .withMisfireHandlingInstructionFireNow())
                .build();

        try {
            context.getScheduler().scheduleJob(retryTrigger);
            log.info("BillingRunJob: retry trigger scheduled for billingRunId={} retryCount={}",
                    billingRunIdStr, nextRetry);
        } catch (SchedulerException se) {
            log.error("BillingRunJob: failed to schedule retry trigger for billingRunId={}: {}",
                    billingRunIdStr, se.getMessage(), se);
        }
    }
}
