-- Scope idempotency keys to (user_id, idempotency_key, action_type)
-- so the same key can NOT be reused across different action types.

-- Backfill any NULL action_type values before adding NOT NULL constraint
UPDATE idempotency_records SET action_type = 'UNKNOWN' WHERE action_type IS NULL;

-- Make action_type NOT NULL
ALTER TABLE idempotency_records ALTER COLUMN action_type SET NOT NULL;

-- Drop the old unique constraint on (user_id, idempotency_key)
ALTER TABLE idempotency_records DROP CONSTRAINT IF EXISTS idempotency_records_user_id_idempotency_key_key;

-- Create new unique constraint on (user_id, idempotency_key, action_type)
ALTER TABLE idempotency_records
    ADD CONSTRAINT uq_idempotency_user_key_action UNIQUE (user_id, idempotency_key, action_type);
