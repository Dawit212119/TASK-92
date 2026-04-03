package com.civicworks.repository;

import com.civicworks.domain.entity.ZoneQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ZoneQueueRepository extends JpaRepository<ZoneQueue, UUID> {

    List<ZoneQueue> findByZoneIdAndStatusOrderByQueuePosition(UUID zoneId, String status);

    @Query("SELECT COALESCE(MAX(q.queuePosition), 0) FROM ZoneQueue q WHERE q.zoneId = :zoneId AND q.status = 'WAITING'")
    int maxQueuePositionForZone(@Param("zoneId") UUID zoneId);

    @Query("""
        SELECT q FROM ZoneQueue q
        JOIN DispatchOrder d ON q.orderId = d.id
        WHERE q.zoneId = :zoneId
          AND q.status = :status
          AND d.organizationId = :orgId
        ORDER BY q.queuePosition
        """)
    List<ZoneQueue> findByZoneAndStatusAndOrg(@Param("zoneId") UUID zoneId,
                                              @Param("status") String status,
                                              @Param("orgId") UUID orgId);
}
