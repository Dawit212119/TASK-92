package com.civicworks.repository;

import com.civicworks.domain.entity.SensitiveWord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SensitiveWordRepository extends JpaRepository<SensitiveWord, UUID> {

    List<SensitiveWord> findByOrganizationId(UUID organizationId);

    Page<SensitiveWord> findByOrganizationId(UUID organizationId, Pageable pageable);
}
