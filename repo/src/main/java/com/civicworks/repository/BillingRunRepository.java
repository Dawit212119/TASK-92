package com.civicworks.repository;

import com.civicworks.domain.entity.BillingRun;
import com.civicworks.domain.enums.BillingRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingRunRepository extends JpaRepository<BillingRun, UUID> {

    Optional<BillingRun> findByIdempotencyKey(String idempotencyKey);

    List<BillingRun> findByStatusIn(List<BillingRunStatus> statuses);
}
