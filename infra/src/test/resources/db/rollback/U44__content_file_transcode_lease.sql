-- Rollback of V44 (transcode lease + attempt accounting). Drop the index before the columns it
-- covers, then the columns. IF EXISTS keeps it idempotent (matches U38's discipline).
DROP INDEX IF EXISTS idx_content_file_transcode_lease;

ALTER TABLE content_file DROP COLUMN IF EXISTS transcode_started_at;
ALTER TABLE content_file DROP COLUMN IF EXISTS transcode_attempts;
ALTER TABLE content_file DROP COLUMN IF EXISTS transcode_last_error;
