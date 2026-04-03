package com.civicworks.service;

import com.civicworks.domain.entity.Notification;
import com.civicworks.domain.entity.NotificationOutbox;
import com.civicworks.domain.entity.User;
import com.civicworks.exception.ResourceNotFoundException;
import com.civicworks.repository.NotificationOutboxRepository;
import com.civicworks.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final NotificationOutboxRepository notificationOutboxRepository;

    @Value("${notification.outbox.email-enabled:false}")
    private boolean emailEnabled;

    @Value("${notification.outbox.sms-enabled:false}")
    private boolean smsEnabled;

    @Value("${notification.outbox.im-enabled:false}")
    private boolean imEnabled;

    public NotificationService(NotificationRepository notificationRepository,
                                NotificationOutboxRepository notificationOutboxRepository) {
        this.notificationRepository = notificationRepository;
        this.notificationOutboxRepository = notificationOutboxRepository;
    }

    @Transactional(readOnly = true)
    public List<Notification> findForUser(UUID userId) {
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Page<Notification> findForUserPage(UUID userId, Pageable pageable) {
        return notificationRepository.findByRecipientId(userId, pageable);
    }

    /**
     * Acknowledges a notification.
     * <p>
     * Object-level authorization: the caller must be the notification's recipient.
     * A non-owner receives the same 404 as a non-existent notification to avoid
     * revealing the existence of other users' notifications.
     */
    @Transactional
    public Notification acknowledge(UUID notificationId, User actor) {
        Notification notification = notificationRepository
                .findByIdAndRecipientId(notificationId, actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification", notificationId));

        if (notification.getReadAt() == null) {
            notification.setReadAt(OffsetDateTime.now());
        }
        notification.setAcknowledgedAt(OffsetDateTime.now());
        return notificationRepository.save(notification);
    }

    /**
     * Creates an in-app notification and, when outbox channels are enabled,
     * persists an outbox row for each enabled channel.  No external sending
     * ever occurs inside this service.
     *
     * @param recipientId     notification recipient
     * @param type            notification type label
     * @param title           short title
     * @param body            full body text
     * @param entityRef       optional reference to the related entity (path)
     * @param recipientAddress address for outbox rows (email/phone/handle); may be null
     *                         when outbox channels are all disabled
     */
    @Transactional
    public Notification createNotification(UUID recipientId, String type, String title,
                                            String body, String entityRef,
                                            String recipientAddress) {
        Notification notification = new Notification();
        notification.setRecipientId(recipientId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setBody(body);
        notification.setEntityRef(entityRef);
        Notification saved = notificationRepository.save(notification);

        // Write outbox rows for every enabled channel — no external sends.
        if (recipientAddress != null && !recipientAddress.isBlank()) {
            if (emailEnabled) {
                writeOutbox(saved.getId(), "EMAIL", recipientAddress, title, body);
            }
            if (smsEnabled) {
                writeOutbox(saved.getId(), "SMS", recipientAddress, title, body);
            }
            if (imEnabled) {
                writeOutbox(saved.getId(), "IM", recipientAddress, title, body);
            }
        }

        return saved;
    }

    /**
     * Backward-compatible overload — no outbox channel address supplied, so only
     * the in-app notification is created regardless of channel configuration.
     */
    @Transactional
    public Notification createNotification(UUID recipientId, String type, String title,
                                            String body, String entityRef) {
        return createNotification(recipientId, type, title, body, entityRef, null);
    }

    // -------------------------------------------------------------------------

    private void writeOutbox(UUID notificationId, String channel,
                              String address, String subject, String body) {
        NotificationOutbox row = new NotificationOutbox();
        row.setNotificationId(notificationId);
        row.setChannel(channel);
        row.setRecipientAddress(address);
        row.setSubject(subject);
        row.setBody(body);
        row.setStatus("PENDING");
        notificationOutboxRepository.save(row);
        log.debug("OUTBOX_WRITTEN notificationId={} channel={}", notificationId, channel);
    }
}
