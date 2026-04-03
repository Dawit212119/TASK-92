package com.civicworks.scheduler;

import com.civicworks.domain.entity.ContentItem;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.ContentState;
import com.civicworks.repository.ContentItemRepository;
import com.civicworks.repository.UserRepository;
import com.civicworks.service.ContentService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@DisallowConcurrentExecution
public class ContentPublishJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ContentPublishJob.class);

    @Autowired
    private ContentItemRepository contentItemRepository;

    @Autowired
    private ContentService contentService;

    @Autowired
    private UserRepository userRepository;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String contentItemIdStr = context.getMergedJobDataMap().getString("contentItemId");
        if (contentItemIdStr == null) {
            log.error("ContentPublishJob: contentItemId not found in job data map");
            return;
        }

        UUID contentItemId = UUID.fromString(contentItemIdStr);
        log.info("Executing ContentPublishJob for contentItemId={}", contentItemId);

        Optional<ContentItem> itemOpt = contentItemRepository.findById(contentItemId);
        if (itemOpt.isEmpty()) {
            log.warn("ContentPublishJob: ContentItem {} not found", contentItemId);
            return;
        }

        ContentItem item = itemOpt.get();
        if (item.getState() != ContentState.SCHEDULED) {
            log.info("ContentPublishJob: ContentItem {} is no longer in SCHEDULED state, skipping", contentItemId);
            return;
        }

        // Use a system/admin actor for scheduled publish
        User systemActor = userRepository.findByUsername("admin")
                .orElseGet(() -> {
                    User dummy = new User();
                    dummy.setId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
                    return dummy;
                });

        try {
            contentService.publishItem(contentItemId, item.getVersion(), systemActor);
            log.info("ContentPublishJob: Successfully published contentItemId={}", contentItemId);
        } catch (Exception e) {
            log.error("ContentPublishJob: Failed to publish contentItemId={}: {}", contentItemId, e.getMessage(), e);
            throw new JobExecutionException("ContentPublishJob failed: " + e.getMessage(), e);
        }
    }
}
