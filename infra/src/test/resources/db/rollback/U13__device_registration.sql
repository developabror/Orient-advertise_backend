DROP INDEX IF EXISTS uq_device_token;
ALTER TABLE device DROP COLUMN IF EXISTS device_token;
ALTER TABLE device DROP COLUMN IF EXISTS registered_at;
