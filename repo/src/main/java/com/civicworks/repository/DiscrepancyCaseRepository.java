package com.civicworks.repository;

import com.civicworks.domain.entity.DiscrepancyCase;
import com.civicworks.domain.enums.DiscrepancyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DiscrepancyCaseRepository extends JpaRepository<DiscrepancyCase, UUID> {

    List<DiscrepancyCase> findByStatus(DiscrepancyStatus status);

    List<DiscrepancyCase> findByAssignedTo(UUID userId);

    Page<DiscrepancyCase> findByStatus(DiscrepancyStatus status, Pageable pageable);

    @Query("SELECT d FROM DiscrepancyCase d WHERE " +
           "(:status IS NULL OR d.status = :status) AND " +
           "(:from IS NULL OR d.createdAt >= :from) AND " +
           "(:to   IS NULL OR d.createdAt <= :to)")
    Page<DiscrepancyCase> findWithFilters(@Param("status") DiscrepancyStatus status,
                                          @Param("from") OffsetDateTime from,
                                          @Param("to") OffsetDateTime to,
                                          Pageable pageable);

    @Query("SELECT d FROM DiscrepancyCase d WHERE d.organizationId = :orgId AND " +
           "(:status IS NULL OR d.status = :status) AND " +
           "(:from IS NULL OR d.createdAt >= :from) AND " +
           "(:to   IS NULL OR d.createdAt <= :to)")
    Page<DiscrepancyCase> findWithFiltersAndOrg(@Param("orgId") UUID orgId,
                                                 @Param("status") DiscrepancyStatus status,
                                                 @Param("from") OffsetDateTime from,
                                                 @Param("to") OffsetDateTime to,
                                                 Pageable pageable);

    Optional<DiscrepancyCase> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
