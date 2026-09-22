-- Rollback of V50 (serial unique among live devices). Postgres; the constraint name is the one V4
-- produced there. Re-adding the global UNIQUE fails while a deleted device and its re-registration
-- share a serial — resolve those rows first.
ALTER TABLE device DROP CONSTRAINT IF EXISTS uq_device_live_serial;
ALTER TABLE device DROP COLUMN IF EXISTS live_serial;
DROP INDEX IF EXISTS idx_device_serial_number;
ALTER TABLE device ADD CONSTRAINT device_serial_number_key UNIQUE (serial_number);
