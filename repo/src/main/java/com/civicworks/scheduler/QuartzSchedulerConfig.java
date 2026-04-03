package com.civicworks.scheduler;

import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;

@Configuration
public class QuartzSchedulerConfig {

    private static final Logger log = LoggerFactory.getLogger(QuartzSchedulerConfig.class);

    private final Scheduler scheduler;

    public QuartzSchedulerConfig(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /**
     * Schedules the LateFeeJob as a daily cron at 00:10 AM.
     */
    public void scheduleRecurringJobs() throws SchedulerException {
        scheduleIfAbsent(
                JobBuilder.newJob(LateFeeJob.class)
                        .withIdentity("lateFeeJob", "billing")
                        .storeDurably()
                        .build(),
                TriggerBuilder.newTrigger()
                        .withIdentity("lateFeeJobTrigger", "billing")
                        .withSchedule(CronScheduleBuilder.cronSchedule("0 10 0 * * ?")
                                .withMisfireHandlingInstructionFireAndProceed())
                        .build()
        );

        scheduleIfAbsent(
                JobBuilder.newJob(SearchHistoryCleanupJob.class)
                        .withIdentity("searchHistoryCleanupJob", "maintenance")
                        .storeDurably()
                        .build(),
                TriggerBuilder.newTrigger()
                        .withIdentity("searchHistoryCleanupTrigger", "maintenance")
                        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 1 * * ?")
                                .withMisfireHandlingInstructionFireAndProceed())
                        .build()
        );

        // Every Monday at 06:00 AM local time — KPI / arrears anomaly detection
        scheduleIfAbsent(
                JobBuilder.newJob(KpiReportJob.class)
                        .withIdentity("kpiReportJob", "reporting")
                        .storeDurably()
                        .build(),
                TriggerBuilder.newTrigger()
                        .withIdentity("kpiReportJobTrigger", "reporting")
                        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 6 ? * MON")
                                .withMisfireHandlingInstructionFireAndProceed())
                        .build()
        );

        // Daily at 08:00 AM — in-app reminders for overdue bills
        scheduleIfAbsent(
                JobBuilder.newJob(ReminderNotificationJob.class)
                        .withIdentity("reminderNotificationJob", "notifications")
                        .storeDurably()
                        .build(),
                TriggerBuilder.newTrigger()
                        .withIdentity("reminderNotificationJobTrigger", "notifications")
                        .withSchedule(CronScheduleBuilder.cronSchedule("0 0 8 * * ?")
                                .withMisfireHandlingInstructionFireAndProceed())
                        .build()
        );
    }

    private void scheduleIfAbsent(JobDetail jobDetail, Trigger trigger) throws SchedulerException {
        if (!scheduler.checkExists(jobDetail.getKey())) {
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("Scheduled job: {}", jobDetail.getKey());
        } else {
            log.info("Job already exists, skipping: {}", jobDetail.getKey());
        }
    }

    /**
     * Dynamically schedules a BillingRunJob at 12:05 AM on the given cycleDate.
     */
    public void scheduleBillingRunJob(UUID billingRunId, LocalDate cycleDate) throws SchedulerException {
        JobDataMap dataMap = new JobDataMap();
        dataMap.put("billingRunId", billingRunId.toString());

        String jobName = "billingRunJob-" + billingRunId;
        JobDetail jobDetail = JobBuilder.newJob(BillingRunJob.class)
                .withIdentity(jobName, "billing")
                .usingJobData(dataMap)
                .storeDurably()
                .requestRecovery()
                .build();

        // 12:05 AM on cycleDate
        Date fireTime = Date.from(
                cycleDate.atTime(0, 5).atZone(ZoneId.systemDefault()).toInstant()
        );

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("billingRunTrigger-" + billingRunId, "billing")
                .startAt(fireTime)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
                .build();

        if (scheduler.checkExists(jobDetail.getKey())) {
            scheduler.deleteJob(jobDetail.getKey());
        }
        scheduler.scheduleJob(jobDetail, trigger);
        log.info("Scheduled BillingRunJob for billingRunId={} at {}", billingRunId, fireTime);
    }

    /**
     * Dynamically schedules a ContentPublishJob at the given scheduledAt time.
     */
    public void scheduleContentPublishJob(UUID contentItemId, OffsetDateTime scheduledAt) throws SchedulerException {
        JobDataMap dataMap = new JobDataMap();
        dataMap.put("contentItemId", contentItemId.toString());

        String jobName = "contentPublishJob-" + contentItemId;
        JobDetail jobDetail = JobBuilder.newJob(ContentPublishJob.class)
                .withIdentity(jobName, "content")
                .usingJobData(dataMap)
                .storeDurably()
                .requestRecovery()
                .build();

        Date fireTime = Date.from(scheduledAt.toInstant());

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity("contentPublishTrigger-" + contentItemId, "content")
                .startAt(fireTime)
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
                .build();

        if (scheduler.checkExists(jobDetail.getKey())) {
            scheduler.deleteJob(jobDetail.getKey());
        }
        scheduler.scheduleJob(jobDetail, trigger);
        log.info("Scheduled ContentPublishJob for contentItemId={} at {}", contentItemId, scheduledAt);
    }
}
