package com.civicworks.repository;

import com.civicworks.domain.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId);

    Page<Notification> findByRecipientId(UUID recipientId, Pageable pageable);

    /** Enforces recipient ownership — returns empty when the caller is not the owner. */
    Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId);
}
