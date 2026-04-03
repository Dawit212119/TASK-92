-- Add organization_id to audit_logs for tenant-scoped reads.
ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS organization_id UUID;

-- Backfill from users.organization_id where possible.
UPDATE audit_logs a
SET organization_id = u.organization_id
FROM users u
WHERE a.actor_id = u.id
  AND a.organization_id IS NULL;

-- Composite index to support org-scoped, time-ordered queries.
CREATE INDEX IF NOT EXISTS idx_audit_logs_org_created_at
    ON audit_logs(organization_id, created_at DESC);

