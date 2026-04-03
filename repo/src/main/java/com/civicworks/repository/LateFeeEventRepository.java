package com.civicworks.repository;

import com.civicworks.domain.entity.LateFeeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LateFeeEventRepository extends JpaRepository<LateFeeEvent, UUID> {

    boolean existsByBillId(UUID billId);

    Optional<LateFeeEvent> findByBillId(UUID billId);
}
