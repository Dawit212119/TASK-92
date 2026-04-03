package com.civicworks.repository;

import com.civicworks.domain.entity.KpiReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface KpiReportRepository extends JpaRepository<KpiReport, UUID> {

    Optional<KpiReport> findByOrganizationIdAndWeekStart(UUID organizationId, LocalDate weekStart);

    /** Most recent KpiReport per organization (used by the GET /reports/kpi endpoint). */
    @Query("""
            SELECT k FROM KpiReport k
            WHERE k.weekStart = (
                SELECT MAX(k2.weekStart) FROM KpiReport k2
                WHERE k2.organizationId = k.organizationId
            )
            ORDER BY k.organizationId
            """)
    List<KpiReport> findLatestPerOrganization();

    @Query("""
            SELECT k FROM KpiReport k
            WHERE k.organizationId = :orgId
              AND k.weekStart = (
                  SELECT MAX(k2.weekStart) FROM KpiReport k2
                  WHERE k2.organizationId = :orgId
              )
            """)
    Optional<KpiReport> findLatestForOrganization(@Param("orgId") UUID orgId);

    @Query("SELECT COALESCE(SUM(b.balanceCents), 0) FROM Bill b " +
           "WHERE b.organizationId = :orgId AND b.status IN ('OPEN', 'OVERDUE')")
    long sumArrearsByOrganization(@Param("orgId") UUID orgId);
}
