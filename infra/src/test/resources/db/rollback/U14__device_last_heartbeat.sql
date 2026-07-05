DROP INDEX IF EXISTS idx_device_last_heartbeat;
ALTER TABLE device DROP COLUMN IF EXISTS last_heartbeat_at;
