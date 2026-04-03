-- Add organization_id to billing_runs for tenant-scoped billing
ALTER TABLE billing_runs
    ADD COLUMN organization_id UUID REFERENCES organizations(id);

CREATE INDEX idx_billing_runs_organization_id ON billing_runs(organization_id);
