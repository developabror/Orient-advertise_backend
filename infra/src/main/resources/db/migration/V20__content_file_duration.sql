-- Duration of the processed video, extracted via FFprobe from the container's
-- format-level duration (variable-frame-rate safe — never computed from
-- frame count / frame rate).
-- A duration of 0 (or null after transcode) is treated as INVALID by the
-- application layer.
ALTER TABLE content_file ADD COLUMN duration_seconds INTEGER;

CREATE INDEX idx_content_file_duration ON content_file (duration_seconds);
