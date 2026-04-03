package com.civicworks.repository;

import com.civicworks.domain.entity.DispatchOrder;
import com.civicworks.domain.enums.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DispatchOrderRepository extends JpaRepository<DispatchOrder, UUID> {

    List<DispatchOrder> findByZoneId(UUID zoneId);

    List<DispatchOrder> findByAssignedDriverId(UUID driverId);

    @Query("SELECT COUNT(d) FROM DispatchOrder d WHERE d.zoneId = :zoneId AND d.status IN :statuses")
    long countActiveOrdersInZone(@Param("zoneId") UUID zoneId, @Param("statuses") List<OrderStatus> statuses);

    List<DispatchOrder> findByStatus(OrderStatus status);

    Optional<DispatchOrder> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
