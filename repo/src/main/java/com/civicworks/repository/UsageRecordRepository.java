package com.civicworks.repository;

import com.civicworks.domain.entity.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

@Repository
public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    /**
     * Returns the quantity recorded for a specific account / fee-item / period,
     * or 0 if no record exists.  Used by billing run calculation for PER_UNIT and
     * METERED fee types.
     */
    @Query("SELECT COALESCE(SUM(u.quantity), 0) FROM UsageRecord u " +
           "WHERE u.accountId = :accountId AND u.feeItemId = :feeItemId " +
           "AND u.billingPeriod = :period")
    long sumQuantity(@Param("accountId") UUID accountId,
                     @Param("feeItemId") UUID feeItemId,
                     @Param("period") LocalDate period);
}
