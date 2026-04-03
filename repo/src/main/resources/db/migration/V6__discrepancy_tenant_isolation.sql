-- V6: Tenant isolation for shift handovers and discrepancy cases.
-- organization_id is derived from the submitting user's org at creation time.

ALTER TABLE shift_handovers
    ADD COLUMN IF NOT EXISTS organization_id UUID REFERENCES organizations(id);

ALTER TABLE discrepancy_cases
    ADD COLUMN IF NOT EXISTS organization_id UUID REFERENCES organizations(id);

CREATE INDEX IF NOT EXISTS idx_discrepancy_cases_org_id ON discrepancy_cases(organization_id);
CREATE INDEX IF NOT EXISTS idx_shift_handovers_org_id ON shift_handovers(organization_id);

-- Backfill: derive org from the submitting user where possible.
UPDATE shift_handovers sh
   SET organization_id = u.organization_id
  FROM users u
 WHERE sh.submitted_by = u.id
   AND sh.organization_id IS NULL;

UPDATE discrepancy_cases dc
   SET organization_id = sh.organization_id
  FROM shift_handovers sh
 WHERE dc.handover_id = sh.id
   AND dc.organization_id IS NULL;
