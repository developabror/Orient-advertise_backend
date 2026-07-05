-- Track who resolved/acknowledged an incident: a username for manual operator action,
-- or the literal string 'system' for automatic resolution by the recovery detector
-- (e.g. heartbeat returns within threshold → DEVICE_OFFLINE auto-resolved).
ALTER TABLE incident ADD COLUMN resolved_by VARCHAR(100);
ALTER TABLE incident ADD COLUMN acknowledged_by VARCHAR(100);
