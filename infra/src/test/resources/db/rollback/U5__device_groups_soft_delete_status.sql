DROP INDEX IF EXISTS idx_device_deleted_at;
DROP INDEX IF EXISTS idx_device_device_group;
DROP INDEX IF EXISTS uq_device_serial_number_active;

ALTER TABLE device DROP CONSTRAINT IF EXISTS fk_device_device_group;
ALTER TABLE device DROP COLUMN IF EXISTS device_group_id;
ALTER TABLE device DROP COLUMN IF EXISTS deleted_at;
ALTER TABLE device ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE device ADD CONSTRAINT device_serial_number_key UNIQUE (serial_number);

DROP INDEX IF EXISTS idx_device_group_region;
DROP TABLE IF EXISTS device_group;
