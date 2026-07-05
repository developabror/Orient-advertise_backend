-- Rollback of V38 (device volume control). Drop the CHECK constraints first, then the columns.
-- IF EXISTS keeps it idempotent. CHECK constraints have no backing index, so order within each
-- group is not load-bearing (unlike FK-backed indexes) — but constraints before columns is tidy.
ALTER TABLE device DROP CONSTRAINT IF EXISTS chk_device_desired_volume;
ALTER TABLE device DROP CONSTRAINT IF EXISTS chk_device_reported_volume;
ALTER TABLE device_group DROP CONSTRAINT IF EXISTS chk_device_group_volume;

ALTER TABLE device DROP COLUMN IF EXISTS desired_volume;
ALTER TABLE device DROP COLUMN IF EXISTS reported_volume;
ALTER TABLE device DROP COLUMN IF EXISTS volume_reported_at;
ALTER TABLE device_group DROP COLUMN IF EXISTS volume;
