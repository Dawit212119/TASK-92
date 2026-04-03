package com.civicworks.controller;

import com.civicworks.domain.entity.KpiReport;
import com.civicworks.repository.KpiReportRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
public class KpiReportController {

    private final KpiReportRepository kpiReportRepository;

    public KpiReportController(KpiReportRepository kpiReportRepository) {
        this.kpiReportRepository = kpiReportRepository;
    }

    /**
     * Returns the most recent {@link KpiReport} for every organisation.
     * Access is restricted to {@code AUDITOR} and {@code SYSTEM_ADMIN} roles.
     */
    @GetMapping("/kpi")
    @PreAuthorize("hasRole('AUDITOR') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<List<KpiReport>> getLatestKpiReports() {
        return ResponseEntity.ok(kpiReportRepository.findLatestPerOrganization());
    }
}
