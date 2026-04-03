package com.civicworks.repository;

import com.civicworks.domain.entity.BillDiscount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillDiscountRepository extends JpaRepository<BillDiscount, UUID> {

    Optional<BillDiscount> findByBillId(UUID billId);

    boolean existsByBillId(UUID billId);
}
