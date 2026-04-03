-- Test data injected before API tests run.
-- Idempotent: uses ON CONFLICT DO NOTHING everywhere.

-- Test account (used by billing tests)
INSERT INTO accounts(id, resident_name, address_line1, organization_id)
VALUES ('00000000-0000-0000-0000-000000000020', 'Test Resident', '1 Main St',
        '00000000-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

-- Reset driver1 dispatch cooldowns so accept-order tests stay green when reusing the same DB volume
DELETE FROM driver_cooldowns WHERE driver_id = '00000000-0000-0000-0000-000000000005';

-- Driver1: ~25 minutes online today (DispatchService requires >= 15 for accept / eligibility API tests)
INSERT INTO driver_online_sessions (id, driver_id, session_start, session_end)
VALUES ('00000000-0000-0000-0000-000000000050',
        '00000000-0000-0000-0000-000000000005',
        NOW() - INTERVAL '25 minutes',
        NOW())
ON CONFLICT (id) DO UPDATE SET
  driver_id = EXCLUDED.driver_id,
  session_start = EXCLUDED.session_start,
  session_end = EXCLUDED.session_end;

-- Pre-built OPEN bill for settlement / late-fee / discount / refund tests.
-- balance_cents=10000 ($100), due_date 60 days in past (past grace period for late-fee tests)
INSERT INTO bills(id, account_id, billing_run_id, cycle_date, due_date,
                  amount_cents, balance_cents, status, version, organization_id)
VALUES ('00000000-0000-0000-0000-000000000030',
        '00000000-0000-0000-0000-000000000020',
        NULL,
        CURRENT_DATE - INTERVAL '60 days',
        CURRENT_DATE - INTERVAL '60 days',
        10000, 10000, 'OPEN', 0,
        '00000000-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

-- Second bill used exclusively by the settlement split tests (kept fresh)
INSERT INTO bills(id, account_id, billing_run_id, cycle_date, due_date,
                  amount_cents, balance_cents, status, version, organization_id)
VALUES ('00000000-0000-0000-0000-000000000031',
        '00000000-0000-0000-0000-000000000020',
        NULL,
        CURRENT_DATE,
        CURRENT_DATE + INTERVAL '30 days',
        9000, 9000, 'OPEN', 0,
        '00000000-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

-- Strip prior settlements/payments/late fees so billing tests stay deterministic when re-seeding the same DB
DELETE FROM refunds WHERE payment_id IN (
  SELECT id FROM payments WHERE bill_id IN (
    '00000000-0000-0000-0000-000000000030', '00000000-0000-0000-0000-000000000031'));
DELETE FROM payments WHERE bill_id IN (
  '00000000-0000-0000-0000-000000000030', '00000000-0000-0000-0000-000000000031');
DELETE FROM settlements WHERE bill_id IN (
  '00000000-0000-0000-0000-000000000030', '00000000-0000-0000-0000-000000000031');
DELETE FROM late_fee_events WHERE bill_id IN (
  '00000000-0000-0000-0000-000000000030', '00000000-0000-0000-0000-000000000031');
DELETE FROM bill_discounts WHERE bill_id IN (
  '00000000-0000-0000-0000-000000000030', '00000000-0000-0000-0000-000000000031');

UPDATE bills SET
  balance_cents = 10000, amount_cents = 10000, status = 'OPEN', version = 0,
  billing_run_id = NULL,
  cycle_date = CURRENT_DATE - INTERVAL '60 days',
  due_date = CURRENT_DATE - INTERVAL '60 days'
WHERE id = '00000000-0000-0000-0000-000000000030';

UPDATE bills SET
  balance_cents = 9000, amount_cents = 9000, status = 'OPEN', version = 0,
  billing_run_id = NULL,
  cycle_date = CURRENT_DATE,
  due_date = CURRENT_DATE + INTERVAL '30 days'
WHERE id = '00000000-0000-0000-0000-000000000031';

-- Content item used by content tests (DRAFT state so it can be published)
INSERT INTO content_items(id, type, title, state, version, organization_id, created_by)
VALUES ('00000000-0000-0000-0000-000000000040',
        'NEWS', 'Seed News Article', 'DRAFT', 0,
        '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000002')
ON CONFLICT DO NOTHING;

-- Published content item for unpublish tests
INSERT INTO content_items(id, type, title, state, version, organization_id, created_by, published_at)
VALUES ('00000000-0000-0000-0000-000000000041',
        'NEWS', 'Published Seed Article', 'PUBLISHED', 0,
        '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000002',
        NOW())
ON CONFLICT DO NOTHING;
