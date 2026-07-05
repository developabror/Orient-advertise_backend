-- Add a lifecycle status column to content_file:
--   UPLOADED   — raw bytes are in MinIO content-raw, transcode not started
--   TRANSCODING — async transcode in progress
--   READY       — processed asset is in MinIO content-processed, ready to play
--   FAILED      — transcode failed; raw bytes may still be in content-raw
ALTER TABLE content_file ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'UPLOADED';
ALTER TABLE content_file ADD CONSTRAINT chk_content_file_status
    CHECK (status IN ('UPLOADED', 'TRANSCODING', 'READY', 'FAILED'));

CREATE INDEX idx_content_file_status ON content_file (status);
