package com.civicworks.repository;

import com.civicworks.domain.entity.Payment;
import com.civicworks.domain.enums.PaymentMethod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByBillId(UUID billId);

    List<Payment> findByShiftId(String shiftId);

    @Query("SELECT p.paymentMethod, SUM(p.amountCents) FROM Payment p WHERE p.shiftId = :shiftId GROUP BY p.paymentMethod")
    List<Object[]> sumAmountByMethodForShift(@Param("shiftId") String shiftId);
}
