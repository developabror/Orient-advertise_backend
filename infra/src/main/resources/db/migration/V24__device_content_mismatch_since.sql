-- Track when a device's reported content version first diverged from the expected
-- server-computed version. Set when heartbeat detects a mismatch and the column is
-- null; cleared when a heartbeat confirms a match. The DeviceHealthMonitor job uses
-- this to escalate to a CONTENT_VERSION_MISMATCH WARNING incident after 30 minutes.
ALTER TABLE device ADD COLUMN content_mismatch_since TIMESTAMP;

CREATE INDEX idx_device_content_mismatch_since ON device (content_mismatch_since);
