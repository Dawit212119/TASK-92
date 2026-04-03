-- V5: Add organization_id to dispatch_orders for tenant isolation.
-- Populated from the creating user's organization at order creation time.
ALTER TABLE dispatch_orders ADD COLUMN IF NOT EXISTS organization_id UUID REFERENCES organizations(id);
CREATE INDEX IF NOT EXISTS idx_dispatch_orders_org_id ON dispatch_orders(organization_id);
