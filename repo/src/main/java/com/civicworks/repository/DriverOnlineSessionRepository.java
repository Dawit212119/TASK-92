package com.civicworks.repository;

import com.civicworks.domain.entity.DriverOnlineSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.UUID;

@Repository
public interface DriverOnlineSessionRepository extends JpaRepository<DriverOnlineSession, UUID> {

    @Query(value = "SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (COALESCE(session_end, NOW()) - session_start))/60), 0) " +
            "FROM driver_online_sessions WHERE driver_id = :driverId AND session_start::date = :date",
            nativeQuery = true)
    Double sumMinutesForDriverOnDate(@Param("driverId") UUID driverId, @Param("date") LocalDate date);
}
