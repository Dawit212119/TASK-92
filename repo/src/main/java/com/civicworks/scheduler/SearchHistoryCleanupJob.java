package com.civicworks.scheduler;

import com.civicworks.service.SearchService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@DisallowConcurrentExecution
public class SearchHistoryCleanupJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(SearchHistoryCleanupJob.class);

    @Autowired
    private SearchService searchService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Executing SearchHistoryCleanupJob");
        try {
            int deleted = searchService.cleanupOldHistory();
            log.info("SearchHistoryCleanupJob: deleted {} records older than 90 days", deleted);
        } catch (Exception e) {
            log.error("SearchHistoryCleanupJob failed: {}", e.getMessage(), e);
            throw new JobExecutionException("SearchHistoryCleanupJob failed: " + e.getMessage(), e);
        }
    }
}
