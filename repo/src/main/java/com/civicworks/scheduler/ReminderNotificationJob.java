package com.civicworks.scheduler;

import com.civicworks.domain.entity.Bill;
import com.civicworks.domain.entity.User;
import com.civicworks.domain.enums.BillStatus;
import com.civicworks.repository.BillRepository;
import com.civicworks.repository.UserRepository;
import com.civicworks.repository.AccountRepository;
import com.civicworks.service.NotificationService;
import com.civicworks.service.NotificationTemplateService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Quartz job that runs daily at 08:00 AM and generates in-app reminder
 * notifications for overdue bills.
 *
 * <p>When outbox channels are enabled via application configuration
 * ({@code notification.outbox.email-enabled} etc.), an outbox row is also
 * written for each enabled channel.  No external network calls are ever made
 * inside this service.
 */
@Component
@DisallowConcurrentExecution
public class ReminderNotificationJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(ReminderNotificationJob.class);

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationTemplateService templateService;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("ReminderNotificationJob started");
        List<Bill> overdueBills = billRepository.findByStatus(BillStatus.OVERDUE);

        int sent = 0;
        for (Bill bill : overdueBills) {
            try {
                // Look up a user associated with the account's organization
                // to receive the reminder.  If the account has no linked user
                // the notification is skipped for this bill.
                accountRepository.findById(bill.getAccountId()).ifPresent(account -> {
                    List<User> orgUsers = account.getOrganizationId() != null
                            ? userRepository.findByOrganizationId(account.getOrganizationId())
                            : List.of();
                    String entityRef = "bills/" + bill.getId();
                    NotificationTemplateService.RenderedTemplate rendered =
                            templateService.render("OVERDUE_BILL_REMINDER", Map.of(
                                    "billId",       bill.getId().toString(),
                                    "balanceCents", String.valueOf(bill.getBalanceCents()),
                                    "entityRef",    entityRef));
                    for (User user : orgUsers) {
                        notificationService.createNotification(
                                user.getId(),
                                "OVERDUE_BILL_REMINDER",
                                rendered.subject(),
                                rendered.body(),
                                entityRef);
                    }
                });
                sent++;
            } catch (Exception e) {
                log.warn("ReminderNotificationJob failed for billId={}: {}", bill.getId(), e.getMessage());
            }
        }

        log.info("ReminderNotificationJob finished: processed={} overdueBills={}", sent, overdueBills.size());
    }
}
