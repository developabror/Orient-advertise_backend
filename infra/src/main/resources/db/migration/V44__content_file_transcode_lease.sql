-- V44: transcode lease + attempt accounting.
--
-- Why: until now a row in UPLOADED had no owner. The async dispatch fired from *inside* the
-- upload transaction, so under READ COMMITTED the transcode thread could SELECT the row before
-- the INSERT committed, find nothing, log a WARN and return — leaving the file stuck in UPLOADED
-- forever. Nothing re-drove it: the only recovery component queried status='TRANSCODING', a state
-- that was never committed (the whole pipeline ran in one transaction and always overwrote the
-- status with a terminal value before commit).
--
-- These columns give the row an owner. A sweeper CLAIMs it with a conditional UPDATE
-- (status CAS + lease stamp + attempt increment), which makes double-dispatch impossible without
-- a distributed lock, and an encode killed mid-flight is detectable by lease age. Attempts cap
-- the retry loop so a poison file cannot spin forever.
--
-- Deliberately keyed on transcode_started_at, NOT updated_at: the pipeline stamps TRANSCODING once
-- and does not touch the row again until the terminal state, so updated_at does not advance during
-- a 15-minute encode and a sweeper keyed on it would double-dispatch a legitimately slow transcode.
--
-- H2 PG-mode for tests, Postgres in prod — both accept this DDL. Nullable-column ALTER precedent:
-- V32/V38/V43. NOT NULL DEFAULT on an ALTER precedent: V17 (status). No interval arithmetic here
-- and none in the queries: every cutoff is bound as a parameter from Java, because H2 and Postgres
-- diverge on interval expressions.
ALTER TABLE content_file ADD COLUMN transcode_started_at TIMESTAMP;
ALTER TABLE content_file ADD COLUMN transcode_attempts   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE content_file ADD COLUMN transcode_last_error VARCHAR(500);

-- Serves both sweeper predicates: (status='TRANSCODING' AND transcode_started_at < :leaseCutoff)
-- and the status-only scans for UPLOADED / FAILED candidates. No partial index — H2 has none.
CREATE INDEX idx_content_file_transcode_lease ON content_file (status, transcode_started_at);
