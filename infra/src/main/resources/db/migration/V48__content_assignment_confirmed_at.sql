-- LOGIC-05: "Replace" must override only its OWN window, not kill the rest of the predecessor.
--
-- A one-week campaign confirmed over a "forever" (year-2100 sentinel) assignment used to retire the
-- predecessor outright, so when the campaign ended the screens went dark. The fix keeps BOTH rows
-- CONFIRMED and overlapping, and decides the overlap by PRECEDENCE instead of by row surgery:
-- during an overlap the MOST RECENTLY CONFIRMED assignment wins, and when it ends the predecessor
-- resumes by itself. Splitting the predecessor into two rows was rejected: it would fork
-- playback_log.assignment_id (proof-of-play) and the (assignment_id, version_number) playback
-- anchors.
--
-- confirmed_at is a NEW column rather than a reuse of updated_at, because updated_at is bumped by
-- bumpVersion() and truncateEndTo() — ordering on it would let an unrelated edit steal precedence.
-- Nullable, because DRAFT/CANCELLED rows were never confirmed; every read uses
-- COALESCE(confirmed_at, created_at) so a pre-V48 row (or a legacy directly-CONFIRMED insert)
-- still orders sensibly.
ALTER TABLE content_assignment ADD COLUMN confirmed_at TIMESTAMP;

-- Backfill: every existing CONFIRMED row was confirmed at some point; created_at is the closest
-- recoverable instant and is what COALESCE would have produced anyway, so the stored value and the
-- fallback agree from day one.
UPDATE content_assignment SET confirmed_at = created_at WHERE status = 'CONFIRMED';

-- The device→playlist resolution rule is embedded in this view THREE times (active_playlist_id,
-- active_playlist_name, computed_status) and its tie-break must match the Java/JPQL copies
-- (ContentAssignment.PRECEDENCE, ContentAssignmentRepository.findActiveAtTime). The ONLY change
-- versus V41 is the ORDER BY:
--     ca.priority DESC, COALESCE(ca.confirmed_at, ca.created_at) DESC, ca.id DESC
-- Every other predicate, column and join is carried over verbatim from V41 (which itself carried
-- V34 verbatim plus d.sync_group_id).
--
-- NOTE: V34 and V41 both define `device_status_view` — V41 superseded V34's definition, so there is
-- exactly ONE live view to recreate here, not two.
--
-- DROP + CREATE (not CREATE OR REPLACE): changing a view is not portable across H2 and PostgreSQL
-- via REPLACE (see V34/V16's own notes). The column list is unchanged from V41.
DROP VIEW IF EXISTS device_status_view;
CREATE VIEW device_status_view AS
SELECT
    d.id, d.region_id, d.facility_id, f.name AS facility_name, d.device_group_id, d.sync_group_id,
    d.serial_number, d.name, d.last_heartbeat_at, d.created_at, d.updated_at,

    -- (1) winning active playlist id — mirrors resolveForDevice's ContentAssignment.PRECEDENCE
    (SELECT ca.playlist_id FROM content_assignment ca
       WHERE ca.deleted_at IS NULL AND ca.status = 'CONFIRMED'
         AND ca.start_time <= CURRENT_TIMESTAMP AND ca.end_time > CURRENT_TIMESTAMP
         AND ( (ca.target_type='REGION'       AND ca.target_id = d.region_id)
            OR (ca.target_type='FACILITY'     AND ca.target_id = d.facility_id)
            OR (ca.target_type='DEVICE_GROUP' AND ca.target_id = d.device_group_id) )
         AND NOT EXISTS (SELECT 1 FROM content_assignment_exclusion x
                          WHERE x.assignment_id = ca.id AND x.device_id = d.id)
       ORDER BY ca.priority DESC, COALESCE(ca.confirmed_at, ca.created_at) DESC, ca.id DESC
       FETCH FIRST 1 ROW ONLY) AS active_playlist_id,

    -- (2) that winning playlist's name (same predicate, joined to playlist)
    (SELECT p.name FROM content_assignment ca JOIN playlist p ON p.id = ca.playlist_id
       WHERE ca.deleted_at IS NULL AND ca.status = 'CONFIRMED'
         AND ca.start_time <= CURRENT_TIMESTAMP AND ca.end_time > CURRENT_TIMESTAMP
         AND ( (ca.target_type='REGION'       AND ca.target_id = d.region_id)
            OR (ca.target_type='FACILITY'     AND ca.target_id = d.facility_id)
            OR (ca.target_type='DEVICE_GROUP' AND ca.target_id = d.device_group_id) )
         AND NOT EXISTS (SELECT 1 FROM content_assignment_exclusion x
                          WHERE x.assignment_id = ca.id AND x.device_id = d.id)
       ORDER BY ca.priority DESC, COALESCE(ca.confirmed_at, ca.created_at) DESC, ca.id DESC
       FETCH FIRST 1 ROW ONLY) AS active_playlist_name,

    -- (3) computed_status — CONFIRMED + exclusion-aware (unchanged from V34/V41; an EXISTS test,
    --     so it carries no ORDER BY: "is there a winner" does not depend on which one wins)
    CASE
      WHEN d.last_heartbeat_at IS NULL THEN 'OFFLINE'
      WHEN d.last_heartbeat_at < CURRENT_TIMESTAMP - INTERVAL '16' MINUTE THEN 'OFFLINE'
      WHEN NOT EXISTS (
          SELECT 1 FROM content_assignment ca
           WHERE ca.deleted_at IS NULL AND ca.status = 'CONFIRMED'
             AND ca.start_time <= CURRENT_TIMESTAMP AND ca.end_time > CURRENT_TIMESTAMP
             AND ( (ca.target_type='REGION'       AND ca.target_id = d.region_id)
                OR (ca.target_type='FACILITY'     AND ca.target_id = d.facility_id)
                OR (ca.target_type='DEVICE_GROUP' AND ca.target_id = d.device_group_id) )
             AND NOT EXISTS (SELECT 1 FROM content_assignment_exclusion x
                              WHERE x.assignment_id = ca.id AND x.device_id = d.id)
      ) THEN 'NO_CONTENT'
      ELSE 'ONLINE'
    END AS computed_status
FROM device d
LEFT JOIN facility f ON f.id = d.facility_id
WHERE d.deleted_at IS NULL;
