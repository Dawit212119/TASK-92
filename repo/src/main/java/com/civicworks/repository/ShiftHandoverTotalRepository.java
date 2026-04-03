package com.civicworks.repository;

import com.civicworks.domain.entity.ShiftHandoverTotal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ShiftHandoverTotalRepository extends JpaRepository<ShiftHandoverTotal, UUID> {

    List<ShiftHandoverTotal> findByHandoverId(UUID handoverId);
}
