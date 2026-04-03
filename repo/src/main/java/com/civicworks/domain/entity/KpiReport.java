package com.civicworks.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "kpi_reports")
public class KpiReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "total_arrears_cents", nullable = false)
    private long totalArrearsCents;

    @Column(name = "prior_week_arrears_cents")
    private Long priorWeekArrearsCents;

    @Column(name = "wow_change_pct", precision = 8, scale = 4)
    private BigDecimal wowChangePct;

    @Column(name = "anomaly_flag", nullable = false)
    private boolean anomalyFlag = false;

    @Column(name = "generated_at", nullable = false)
    private OffsetDateTime generatedAt;

    public KpiReport() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }

    public LocalDate getWeekStart() { return weekStart; }
    public void setWeekStart(LocalDate weekStart) { this.weekStart = weekStart; }

    public long getTotalArrearsCents() { return totalArrearsCents; }
    public void setTotalArrearsCents(long totalArrearsCents) { this.totalArrearsCents = totalArrearsCents; }

    public Long getPriorWeekArrearsCents() { return priorWeekArrearsCents; }
    public void setPriorWeekArrearsCents(Long priorWeekArrearsCents) { this.priorWeekArrearsCents = priorWeekArrearsCents; }

    public BigDecimal getWowChangePct() { return wowChangePct; }
    public void setWowChangePct(BigDecimal wowChangePct) { this.wowChangePct = wowChangePct; }

    public boolean isAnomalyFlag() { return anomalyFlag; }
    public void setAnomalyFlag(boolean anomalyFlag) { this.anomalyFlag = anomalyFlag; }

    public OffsetDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(OffsetDateTime generatedAt) { this.generatedAt = generatedAt; }
}
