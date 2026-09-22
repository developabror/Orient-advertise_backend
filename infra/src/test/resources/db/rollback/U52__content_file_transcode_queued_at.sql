-- Rollback of V52 (transcode queued-at stamp). IF EXISTS keeps it idempotent.
ALTER TABLE content_file DROP COLUMN IF EXISTS transcode_queued_at;
