package com.civicworks.repository;

import com.civicworks.domain.entity.ShiftHandover;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ShiftHandoverRepository extends JpaRepository<ShiftHandover, UUID> {

    List<ShiftHandover> findByShiftId(String shiftId);
}
