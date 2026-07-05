-- Track content version per device + per assignment.
-- Device records the version it currently has cached (reported on heartbeat).
-- Assignment carries a monotonically-increasing version counter so a rollback to
-- prior playlist content still yields a NEW version (devices can tell rollback apart
-- from no-change).
ALTER TABLE device ADD COLUMN current_content_version VARCHAR(64);

ALTER TABLE content_assignment ADD COLUMN version_number INTEGER NOT NULL DEFAULT 1;

CREATE INDEX idx_device_content_version ON device (current_content_version);
