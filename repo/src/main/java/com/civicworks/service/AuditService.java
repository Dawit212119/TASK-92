package com.civicworks.service;

import com.civicworks.domain.entity.AuditLog;
import com.civicworks.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(UUID actorId, String action, String entityRef, Map<String, Object> details) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setAction(action);
        entry.setEntityRef(entityRef);
        try {
            entry.setDetails(objectMapper.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            entry.setDetails("{}");
        }
        auditLogRepository.save(entry);
        log.info("AUDIT action={} entityRef={} actorId={}", action, entityRef, actorId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(UUID actorId, String action, String entityRef, String rawJson) {
        // Backward-compatible overload — stores as-is (caller is responsible for valid JSON)
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setAction(action);
        entry.setEntityRef(entityRef);
        entry.setDetails(rawJson);
        auditLogRepository.save(entry);
        log.info("AUDIT action={} entityRef={} actorId={}", action, entityRef, actorId);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> findAll(Pageable pageable) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> findWithFilters(String entityRef, String action,
                                           OffsetDateTime from, OffsetDateTime to,
                                           Pageable pageable) {
        return auditLogRepository.findWithFilters(entityRef, action, from, to, pageable);
    }
}
