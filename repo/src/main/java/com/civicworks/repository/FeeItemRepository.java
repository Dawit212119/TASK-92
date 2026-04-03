package com.civicworks.repository;

import com.civicworks.domain.entity.FeeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FeeItemRepository extends JpaRepository<FeeItem, UUID> {

    Optional<FeeItem> findByCode(String code);

    List<FeeItem> findByOrganizationId(UUID organizationId);
}
