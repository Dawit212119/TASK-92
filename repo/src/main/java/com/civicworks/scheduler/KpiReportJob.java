package com.civicworks.scheduler;

import com.civicworks.repository.OrganizationRepository;
import com.civicworks.service.KpiService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Quartz job that runs every Monday at 06:00 AM (local time).
 * Invokes {@link KpiService#generateWeeklyReport(java.util.UUID)} for every
 * organisation in the system and logs an anomaly warning when week-over-week
 * arrears growth exceeds 15%.
 */
@Component
@DisallowConcurrentExecution
public class KpiReportJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(KpiReportJob.class);

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private KpiService kpiService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("KpiReportJob started");
        int processed = 0;
        int failed    = 0;

        for (var org : organizationRepository.findAll()) {
            try {
                kpiService.generateWeeklyReport(org.getId());
                processed++;
            } catch (Exception e) {
                log.error("KpiReportJob failed for orgId={}: {}", org.getId(), e.getMessage(), e);
                failed++;
            }
        }

        log.info("KpiReportJob finished: processed={} failed={}", processed, failed);
    }
}
