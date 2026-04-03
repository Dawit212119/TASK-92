package com.civicworks.service;

import com.civicworks.domain.entity.KpiReport;
import com.civicworks.repository.KpiReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class KpiService {

    private static final Logger log = LoggerFactory.getLogger(KpiService.class);

    private static final double ANOMALY_THRESHOLD_PCT = 15.0;

    private final KpiReportRepository kpiReportRepository;

    public KpiService(KpiReportRepository kpiReportRepository) {
        this.kpiReportRepository = kpiReportRepository;
    }

    /**
     * Generates (or replaces) the weekly KPI report for the given organisation.
     *
     * <p>Week boundaries are aligned to ISO Monday.  The report is for the week
     * that started on the most recent Monday on or before today; the prior-week
     * row (week_start = previous Monday) is used for the WoW comparison.
     *
     * <p>An anomaly is flagged when {@code wow_change_pct > 15.0} and the prior
     * week had a non-zero arrears balance (division-by-zero guard).
     */
    @Transactional
    public KpiReport generateWeeklyReport(UUID orgId) {
        LocalDate thisMonday  = mostRecentMonday(LocalDate.now());
        LocalDate priorMonday = thisMonday.minusWeeks(1);

        // --- current arrears (OPEN + OVERDUE bills) ---
        long currentArrears = kpiReportRepository.sumArrearsByOrganization(orgId);

        // --- prior week ---
        Long priorArrears = kpiReportRepository
                .findByOrganizationIdAndWeekStart(orgId, priorMonday)
                .map(KpiReport::getTotalArrearsCents)
                .orElse(null);

        // --- WoW % change and anomaly flag ---
        BigDecimal wowPct    = null;
        boolean    anomaly   = false;

        if (priorArrears != null && priorArrears > 0) {
            double pct = ((double) (currentArrears - priorArrears) / priorArrears) * 100.0;
            wowPct  = BigDecimal.valueOf(pct).setScale(4, RoundingMode.HALF_UP);
            anomaly = pct > ANOMALY_THRESHOLD_PCT;
        }

        // --- upsert ---
        KpiReport report = kpiReportRepository
                .findByOrganizationIdAndWeekStart(orgId, thisMonday)
                .orElseGet(KpiReport::new);

        report.setOrganizationId(orgId);
        report.setWeekStart(thisMonday);
        report.setTotalArrearsCents(currentArrears);
        report.setPriorWeekArrearsCents(priorArrears);
        report.setWowChangePct(wowPct);
        report.setAnomalyFlag(anomaly);
        report.setGeneratedAt(OffsetDateTime.now());

        KpiReport saved = kpiReportRepository.save(report);

        if (anomaly) {
            log.warn("KPI_ANOMALY_DETECTED orgId={} pct={}", orgId,
                    wowPct != null ? wowPct.toPlainString() : "N/A");
        }

        return saved;
    }

    // -------------------------------------------------------------------------

    /** Returns the most recent Monday on or before the given date (ISO week). */
    public static LocalDate mostRecentMonday(LocalDate date) {
        return date.with(DayOfWeek.MONDAY).isAfter(date)
                ? date.with(DayOfWeek.MONDAY).minusWeeks(1)
                : date.with(DayOfWeek.MONDAY);
    }
}
