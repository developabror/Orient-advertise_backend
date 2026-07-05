-- Track in-flight sync state per device.
-- When the server returns a non-empty sync plan, sync_pending_version + sync_pending_since
-- are set so a background monitor can escalate to an incident if the device fails to
-- confirm within 30 minutes. On successful confirm, both are cleared.
-- sync_pending_since is set ONCE on the first /sync call and is NOT refreshed on subsequent
-- calls, so the 30-minute timeout is measured from the first issued sync plan.
ALTER TABLE device ADD COLUMN sync_pending_version VARCHAR(64);
ALTER TABLE device ADD COLUMN sync_pending_since TIMESTAMP;

CREATE INDEX idx_device_sync_pending_since ON device (sync_pending_since);
