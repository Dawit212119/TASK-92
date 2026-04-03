package com.civicworks.repository;

import com.civicworks.domain.entity.Bill;
import com.civicworks.domain.enums.BillStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillRepository extends JpaRepository<Bill, UUID> {

    List<Bill> findByAccountId(UUID accountId);

    List<Bill> findByStatus(BillStatus status);

    List<Bill> findByOrganizationId(UUID organizationId);

    @Query("SELECT b FROM Bill b WHERE b.dueDate < :cutoffDate AND b.status NOT IN :excludedStatuses")
    List<Bill> findOverdueBills(@Param("cutoffDate") LocalDate cutoffDate,
                                @Param("excludedStatuses") List<BillStatus> excludedStatuses);

    List<Bill> findByBillingRunId(UUID billingRunId);

    @Query("SELECT b FROM Bill b WHERE " +
           "(:accountId IS NULL OR b.accountId = :accountId) AND " +
           "(:status IS NULL OR CAST(b.status AS string) = :status)")
    Page<Bill> findWithFilters(@Param("accountId") UUID accountId,
                                @Param("status") String status,
                                Pageable pageable);

    /**
     * Org-scoped list query.  When {@code orgId} is non-null only bills belonging
     * to that organisation are returned, enforcing tenant isolation.
     * SYSTEM_ADMIN passes {@code null} to see all organisations.
     */
    @Query("SELECT b FROM Bill b WHERE " +
           "(:orgId IS NULL OR b.organizationId = :orgId) AND " +
           "(:accountId IS NULL OR b.accountId = :accountId) AND " +
           "(:status IS NULL OR CAST(b.status AS string) = :status)")
    Page<Bill> findWithFiltersAndOrg(@Param("orgId") UUID orgId,
                                      @Param("accountId") UUID accountId,
                                      @Param("status") String status,
                                      Pageable pageable);

    /** Org-scoped single-bill lookup — returns empty when orgId does not match. */
    Optional<Bill> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
