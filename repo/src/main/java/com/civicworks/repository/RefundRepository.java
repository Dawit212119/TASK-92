package com.civicworks.repository;

import com.civicworks.domain.entity.Refund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByPaymentId(UUID paymentId);

    @Query("SELECT COALESCE(SUM(r.refundAmountCents), 0) FROM Refund r WHERE r.paymentId = :paymentId")
    long sumRefundsByPaymentId(@Param("paymentId") UUID paymentId);
}
