package com.civicworks.repository;

import com.civicworks.domain.entity.DriverCooldown;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface DriverCooldownRepository extends JpaRepository<DriverCooldown, UUID> {

    @Query("SELECT d FROM DriverCooldown d WHERE d.driverId = :driverId AND d.cooldownUntil > :now")
    List<DriverCooldown> findActiveCooldownsForDriver(@Param("driverId") UUID driverId, @Param("now") OffsetDateTime now);

    boolean existsByDriverIdAndCooldownUntilAfter(UUID driverId, OffsetDateTime now);
}
