package com.civicworks.repository;

import com.civicworks.domain.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByEntityRef(String entityRef);

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<AuditLog> findAllByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    @Query("""
           SELECT a FROM AuditLog a
           WHERE (:orgId IS NULL OR a.organizationId = :orgId)
             AND (:entityRef IS NULL OR a.entityRef = :entityRef)
             AND (:action IS NULL OR a.action = :action)
             AND (:from IS NULL OR a.createdAt >= :from)
             AND (:to IS NULL OR a.createdAt <= :to)
           ORDER BY a.createdAt DESC
           """)
    Page<AuditLog> findWithFilters(@Param("orgId") UUID orgId,
                                   @Param("entityRef") String entityRef,
                                   @Param("action") String action,
                                   @Param("from") OffsetDateTime from,
                                   @Param("to") OffsetDateTime to,
                                   Pageable pageable);
}
