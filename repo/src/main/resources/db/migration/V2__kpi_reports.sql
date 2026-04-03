CREATE TABLE kpi_reports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    week_start DATE NOT NULL,
    total_arrears_cents BIGINT NOT NULL DEFAULT 0,
    prior_week_arrears_cents BIGINT,
    wow_change_pct NUMERIC(8,4),
    anomaly_flag BOOLEAN NOT NULL DEFAULT FALSE,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(organization_id, week_start)
);
