DROP INDEX IF EXISTS idx_device_content_mismatch_since;
ALTER TABLE device DROP COLUMN IF EXISTS content_mismatch_since;
