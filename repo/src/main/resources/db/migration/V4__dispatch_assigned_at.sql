-- V4: Add assigned_at timestamp for forced-dispatch 30-minute reassignment rule
ALTER TABLE dispatch_orders ADD COLUMN IF NOT EXISTS assigned_at TIMESTAMPTZ;
