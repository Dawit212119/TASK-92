package com.civicworks.controller;

import com.civicworks.config.AuthUtils;
import com.civicworks.domain.entity.AuditLog;
import com.civicworks.domain.entity.User;
import com.civicworks.service.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService auditService;
    private final AuthUtils authUtils;

    public AuditController(AuditService auditService, AuthUtils authUtils) {
        this.auditService = auditService;
        this.authUtils = authUtils;
    }

    @GetMapping("/logs")
    @PreAuthorize("hasRole('AUDITOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, Object>> getLogs(
            Authentication authentication,
            @RequestParam(required = false) String entity_ref,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {

        User actor = authUtils.resolveUser(authentication);
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(200, size));
        Page<AuditLog> result = (entity_ref != null || action != null || from != null || to != null)
                ? auditService.findWithFilters(actor, entity_ref, action, from, to, pageable)
                : auditService.findAll(actor, pageable);

        return ResponseEntity.ok(Map.of(
                "data",  result.getContent(),
                "page",  result.getNumber(),
                "size",  result.getSize(),
                "total", result.getTotalElements()
        ));
    }
}
