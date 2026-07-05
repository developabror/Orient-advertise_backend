DROP INDEX IF EXISTS idx_device_sync_pending_since;
ALTER TABLE device DROP COLUMN IF EXISTS sync_pending_since;
ALTER TABLE device DROP COLUMN IF EXISTS sync_pending_version;
