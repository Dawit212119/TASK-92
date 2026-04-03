package com.civicworks.service;

import com.civicworks.domain.entity.AuditLog;
import com.civicworks.domain.entity.User;
import com.civicworks.repository.AuditLogRepository;
import com.civicworks.repository.UserRepository;
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
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogRepository, UserRepository userRepository,
                        ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(UUID actorId, String action, String entityRef, Map<String, Object> details) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setOrganizationId(resolveOrgIdForActor(actorId));
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
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setOrganizationId(resolveOrgIdForActor(actorId));
        entry.setAction(action);
        entry.setEntityRef(entityRef);
        entry.setDetails(rawJson);
        auditLogRepository.save(entry);
        log.info("AUDIT action={} entityRef={} actorId={}", action, entityRef, actorId);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> findAll(User actor, Pageable pageable) {
        UUID orgId = AuthorizationService.resolveOrgId(actor);
        if (orgId == null) {
            return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return auditLogRepository.findAllByOrganizationIdOrderByCreatedAtDesc(orgId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> findWithFilters(User actor, String entityRef, String action,
                                           OffsetDateTime from, OffsetDateTime to,
                                           Pageable pageable) {
        UUID orgId = AuthorizationService.resolveOrgId(actor);
        return auditLogRepository.findWithFilters(orgId, entityRef, action, from, to, pageable);
    }

    private UUID resolveOrgIdForActor(UUID actorId) {
        if (actorId == null) return null;
        return userRepository.findById(actorId)
                .map(user -> user.getOrganization() != null ? user.getOrganization().getId() : null)
                .orElse(null);
    }
}
