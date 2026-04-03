package com.civicworks.repository;

import com.civicworks.domain.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> {

    Optional<IdempotencyRecord> findByUserIdAndIdempotencyKeyAndActionType(UUID userId, String idempotencyKey, String actionType);

    boolean existsByUserIdAndIdempotencyKeyAndActionTypeNot(UUID userId, String idempotencyKey, String actionType);
}
