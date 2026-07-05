-- Track last heartbeat for status derivation.
-- Status is computed from last_heartbeat_at + content assignment state:
--   > 15min since last heartbeat (with 60s grace) → OFFLINE
--   no resolvable content → NO_CONTENT
--   else → ONLINE
ALTER TABLE device ADD COLUMN last_heartbeat_at TIMESTAMP;

CREATE INDEX idx_device_last_heartbeat ON device (last_heartbeat_at);
