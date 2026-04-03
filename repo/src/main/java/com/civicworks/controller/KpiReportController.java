package com.civicworks.controller;

import com.civicworks.config.AuthUtils;
import com.civicworks.domain.entity.KpiReport;
import com.civicworks.domain.entity.User;
import com.civicworks.repository.KpiReportRepository;
import com.civicworks.service.AuthorizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
public class KpiReportController {

    private final KpiReportRepository kpiReportRepository;
    private final AuthUtils authUtils;

    public KpiReportController(KpiReportRepository kpiReportRepository, AuthUtils authUtils) {
        this.kpiReportRepository = kpiReportRepository;
        this.authUtils = authUtils;
    }

    @GetMapping("/kpi")
    @PreAuthorize("hasRole('AUDITOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<List<KpiReport>> getLatestKpiReports(Authentication authentication) {
        User actor = authUtils.resolveUser(authentication);
        UUID orgId = AuthorizationService.resolveOrgId(actor);

        if (orgId == null) {
            return ResponseEntity.ok(kpiReportRepository.findLatestPerOrganization());
        }

        List<KpiReport> result = kpiReportRepository.findLatestForOrganization(orgId)
                .map(List::of)
                .orElseGet(List::of);
        return ResponseEntity.ok(result);
    }
}
