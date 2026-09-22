-- LOGIC-08: separate "claimed and waiting in the transcode queue" from "encode actually running".
--
-- Until now the claim stamped transcode_started_at, so the 20-minute lease started ticking while
-- the job was merely queued. A job that waited longer than that behind other encodes on a
-- single-width pool looked crashed: the sweeper re-claimed it (attempts + 1) and queued a duplicate,
-- and after three rounds marked it FAILED ("Abandoned after 3 transcode attempt(s)") — a file that
-- was never encoded at all.
--
-- From V52 on:
--   transcode_queued_at  — when the row was last claimed and handed to the queue;
--   transcode_started_at — when an encode actually began (NULL while the job is still queued);
--   transcode_attempts   — encodes actually started (was: claims).
-- The lease (started_at) therefore covers only real work, and the start guard only lets a task
-- through while started_at IS NULL, so duplicate queue entries can never run two encodes.
ALTER TABLE content_file ADD COLUMN transcode_queued_at TIMESTAMP;

-- Rows claimed under the old rules: their transcode_attempts counted claims, and transcode_started_at
-- holds a claim time, not a start. A deploy restarts the application, so no task holds them any more:
-- make them "queued, never started" with a fresh budget, and the boot sweep re-queues them. Left as
-- they were, the boot sweep would abandon any with three counted claims — LOGIC-08 one last time.
-- (FAILED rows are left alone: a pre-V52 "Abandoned after 3 transcode attempt(s)" may be a queue-wait
-- victim or a real poison file, and only an operator's retranscode should decide.)
UPDATE content_file
   SET transcode_attempts = 0,
       transcode_started_at = NULL
 WHERE status = 'TRANSCODING';
