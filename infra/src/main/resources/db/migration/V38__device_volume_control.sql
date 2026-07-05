-- Remote device volume control: persistent desired state reconciled on heartbeat.
-- effective volume = device.desired_volume ?? device_group.volume ?? DEFAULT (100).
-- All columns are nullable (NULL = inherit / no group default), so no backfill is needed.
-- CHECK precedent: V18. H2 PG-mode for tests, Postgres in prod — both accept these.

ALTER TABLE device ADD COLUMN desired_volume INT;
ALTER TABLE device ADD COLUMN reported_volume INT;
ALTER TABLE device ADD COLUMN volume_reported_at TIMESTAMP;
ALTER TABLE device_group ADD COLUMN volume INT;

ALTER TABLE device ADD CONSTRAINT chk_device_desired_volume
    CHECK (desired_volume IS NULL OR (desired_volume BETWEEN 0 AND 100));
ALTER TABLE device ADD CONSTRAINT chk_device_reported_volume
    CHECK (reported_volume IS NULL OR (reported_volume BETWEEN 0 AND 100));
ALTER TABLE device_group ADD CONSTRAINT chk_device_group_volume
    CHECK (volume IS NULL OR (volume BETWEEN 0 AND 100));
