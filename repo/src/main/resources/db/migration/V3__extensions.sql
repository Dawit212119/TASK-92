-- V3: usage records, content-item search fields, notification outbox

-- Usage records: per-account, per-fee-item quantity counters used by billing runs.
-- PER_UNIT and METERED fee items multiply rateCents by the quantity stored here.
CREATE TABLE usage_records (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    account_id UUID NOT NULL REFERENCES accounts(id),
    fee_item_id UUID NOT NULL REFERENCES fee_items(id),
    quantity BIGINT NOT NULL DEFAULT 0,
    billing_period DATE NOT NULL,   -- matches billing_run.cycle_date
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, fee_item_id, billing_period)
);

CREATE INDEX idx_usage_records_account_period ON usage_records(account_id, billing_period);

-- Content-item search extension fields
ALTER TABLE content_items
    ADD COLUMN IF NOT EXISTS price_cents BIGINT DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS origin     VARCHAR(255) DEFAULT NULL;

-- Notification outbox: rows written when a notification channel (email/SMS/IM)
-- is enabled; no external send ever happens inside this service — the outbox is
-- consumed by an external relay.  All channels are disabled by default.
CREATE TABLE IF NOT EXISTS notification_outbox (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    notification_id UUID NOT NULL REFERENCES notifications(id),
    channel VARCHAR(20) NOT NULL,       -- EMAIL | SMS | IM
    recipient_address TEXT NOT NULL,    -- email addr / phone / IM handle
    subject VARCHAR(500),
    body TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ                 -- set by external relay on pickup
);

CREATE INDEX IF NOT EXISTS idx_notification_outbox_status ON notification_outbox(status);
CREATE INDEX IF NOT EXISTS idx_notification_outbox_notif_id ON notification_outbox(notification_id);
