package com.civicworks.unit;

import com.civicworks.domain.entity.KpiReport;
import com.civicworks.repository.KpiReportRepository;
import com.civicworks.service.KpiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KpiService#generateWeeklyReport(UUID)}.
 *
 * The service is tested with mocked repositories so no database is needed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KpiServiceTest {

    @Mock
    private KpiReportRepository kpiReportRepository;

    private KpiService kpiService;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        kpiService = new KpiService(kpiReportRepository);
        // By default, no existing report for current week (new report scenario)
        when(kpiReportRepository.findByOrganizationIdAndWeekStart(eq(orgId), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(kpiReportRepository.save(any(KpiReport.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── anomaly_flag set when WoW growth > 15% ───────────────────────────────

    @Test
    void wowGrowthAbove15Pct_anomalyFlagSet() {
        long priorArrears   = 100_000L; // $1 000.00
        long currentArrears = 120_000L; // $1 200.00  → 20% growth

        stubPriorReport(priorArrears);
        when(kpiReportRepository.sumArrearsByOrganization(orgId)).thenReturn(currentArrears);

        KpiReport report = kpiService.generateWeeklyReport(orgId);

        assertThat(report.isAnomalyFlag()).isTrue();
        assertThat(report.getWowChangePct())
                .isNotNull()
                .isGreaterThan(BigDecimal.valueOf(15.0));
    }

    @Test
    void wowGrowthExactly16Pct_anomalyFlagSet() {
        long priorArrears   = 100_000L;
        long currentArrears = 116_000L; // exactly 16%

        stubPriorReport(priorArrears);
        when(kpiReportRepository.sumArrearsByOrganization(orgId)).thenReturn(currentArrears);

        KpiReport report = kpiService.generateWeeklyReport(orgId);

        assertThat(report.isAnomalyFlag()).isTrue();
    }

    // ── anomaly_flag not set when WoW growth ≤ 15% ───────────────────────────

    @Test
    void wowGrowthBelow15Pct_anomalyFlagNotSet() {
        long priorArrears   = 100_000L;
        long currentArrears = 110_000L; // 10% growth

        stubPriorReport(priorArrears);
        when(kpiReportRepository.sumArrearsByOrganization(orgId)).thenReturn(currentArrears);

        KpiReport report = kpiService.generateWeeklyReport(orgId);

        assertThat(report.isAnomalyFlag()).isFalse();
        assertThat(report.getWowChangePct())
                .isNotNull()
                .isLessThanOrEqualTo(BigDecimal.valueOf(15.0));
    }

    @Test
    void wowGrowthExactly15Pct_anomalyFlagNotSet() {
        long priorArrears   = 100_000L;
        long currentArrears = 115_000L; // exactly 15%

        stubPriorReport(priorArrears);
        when(kpiReportRepository.sumArrearsByOrganization(orgId)).thenReturn(currentArrears);

        KpiReport report = kpiService.generateWeeklyReport(orgId);

        assertThat(report.isAnomalyFlag()).isFalse();
    }

    @Test
    void wowGrowthNegative_anomalyFlagNotSet() {
        long priorArrears   = 100_000L;
        long currentArrears = 80_000L; // arrears fell

        stubPriorReport(priorArrears);
        when(kpiReportRepository.sumArrearsByOrganization(orgId)).thenReturn(currentArrears);

        KpiReport report = kpiService.generateWeeklyReport(orgId);

        assertThat(report.isAnomalyFlag()).isFalse();
        assertThat(report.getWowChangePct()).isNotNull().isNegative();
    }

    // ── first-week report (no prior week) ────────────────────────────────────

    @Test
    void noPriorWeekReport_anomalyFlagFalse_wowPctNull() {
        when(kpiReportRepository.sumArrearsByOrganization(orgId)).thenReturn(50_000L);
        // No prior report is already the default stub

        KpiReport report = kpiService.generateWeeklyReport(orgId);

        assertThat(report.isAnomalyFlag()).isFalse();
        assertThat(report.getWowChangePct()).isNull();
        assertThat(report.getPriorWeekArrearsCents()).isNull();
    }

    @Test
    void priorWeekArrearsZero_anomalyFlagFalse_wowPctNull() {
        // Division-by-zero guard: prior arrears = 0 → no WoW computation
        stubPriorReport(0L);
        when(kpiReportRepository.sumArrearsByOrganization(orgId)).thenReturn(50_000L);

        KpiReport report = kpiService.generateWeeklyReport(orgId);

        assertThat(report.isAnomalyFlag()).isFalse();
        assertThat(report.getWowChangePct()).isNull();
    }

    // ── report fields are persisted correctly ─────────────────────────────────

    @Test
    void reportSaved_withCorrectWeekStart() {
        when(kpiReportRepository.sumArrearsByOrganization(orgId)).thenReturn(10_000L);

        kpiService.generateWeeklyReport(orgId);

        ArgumentCaptor<KpiReport> captor = ArgumentCaptor.forClass(KpiReport.class);
        verify(kpiReportRepository).save(captor.capture());

        KpiReport saved = captor.getValue();
        assertThat(saved.getWeekStart().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getTotalArrearsCents()).isEqualTo(10_000L);
        assertThat(saved.getGeneratedAt()).isNotNull();
    }

    @Test
    void existingReport_isUpserted_notDuplicated() {
        // When a row already exists for this week, it should be updated, not inserted
        KpiReport existing = new KpiReport();
        existing.setOrganizationId(orgId);
        existing.setWeekStart(KpiService.mostRecentMonday(LocalDate.now()));

        when(kpiReportRepository.findByOrganizationIdAndWeekStart(eq(orgId), any(LocalDate.class)))
                .thenReturn(Optional.of(existing));
        when(kpiReportRepository.sumArrearsByOrganization(orgId)).thenReturn(5_000L);

        kpiService.generateWeeklyReport(orgId);

        // save() must be called exactly once (upsert), not twice
        verify(kpiReportRepository, times(1)).save(same(existing));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Stubs the prior-week KpiReport with the given arrears total.
     * The prior week is always {@code thisMonday - 7 days}.
     */
    private void stubPriorReport(long arrearsCents) {
        LocalDate priorMonday = KpiService.mostRecentMonday(LocalDate.now()).minusWeeks(1);
        KpiReport prior = new KpiReport();
        prior.setOrganizationId(orgId);
        prior.setWeekStart(priorMonday);
        prior.setTotalArrearsCents(arrearsCents);

        when(kpiReportRepository.findByOrganizationIdAndWeekStart(eq(orgId), eq(priorMonday)))
                .thenReturn(Optional.of(prior));
    }
}
