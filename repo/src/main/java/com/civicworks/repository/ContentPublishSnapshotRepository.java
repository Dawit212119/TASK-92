package com.civicworks.repository;

import com.civicworks.domain.entity.ContentPublishSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ContentPublishSnapshotRepository extends JpaRepository<ContentPublishSnapshot, UUID> {

    List<ContentPublishSnapshot> findByContentItemIdOrderByCreatedAtDesc(UUID contentItemId);
}
