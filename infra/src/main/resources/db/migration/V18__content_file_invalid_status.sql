-- Extend content_file status to include INVALID for files that pass pre-upload
-- validation (MIME/extension/size) but fail post-upload FFprobe inspection
-- (corrupt, password-protected, no video streams, etc.).
ALTER TABLE content_file DROP CONSTRAINT IF EXISTS chk_content_file_status;
ALTER TABLE content_file ADD CONSTRAINT chk_content_file_status
    CHECK (status IN ('UPLOADED', 'TRANSCODING', 'READY', 'FAILED', 'INVALID'));

ALTER TABLE content_file ADD COLUMN invalid_reason VARCHAR(500);
