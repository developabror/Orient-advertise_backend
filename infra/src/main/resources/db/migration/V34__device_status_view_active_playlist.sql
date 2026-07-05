-- Recreate device_status_view to expose the device's currently-resolved active playlist
-- (id + name) at the list level, and to fix computed_status so it mirrors
-- ContentAssignmentService.resolveForDevice exactly (CONFIRMED + half-open window +
-- target match + NOT excluded). The pre-V34 view (V15/V16) checked window+target but
-- omitted status='CONFIRMED' and the exclusion test, so a device whose only assignment
-- was DRAFT/CANCELLED/excluded was wrongly reported ONLINE instead of NO_CONTENT.
--
-- DROP + CREATE (not CREATE OR REPLACE): changing a view's column list is not portable
-- across H2 and PostgreSQL via REPLACE (see V16's own note).
--
-- The active-assignment predicate appears THREE times below (active_playlist_id,
-- active_playlist_name, computed_status). They MUST stay character-identical — they are
-- one logical predicate, and a fidelity test pins that all three agree on every row.
DROP VIEW IF EXISTS device_status_view;
CREATE VIEW device_status_view AS
SELECT
    d.id, d.region_id, d.facility_id, f.name AS facility_name, d.device_group_id,
    d.serial_number, d.name, d.last_heartbeat_at, d.created_at, d.updated_at,

    -- (1) winning active playlist id — mirrors resolveForDevice .max(priority), tie-broken id DESC
    (SELECT ca.playlist_id FROM content_assignment ca
       WHERE ca.deleted_at IS NULL AND ca.status = 'CONFIRMED'
         AND ca.start_time <= CURRENT_TIMESTAMP AND ca.end_time > CURRENT_TIMESTAMP
         AND ( (ca.target_type='REGION'       AND ca.target_id = d.region_id)
            OR (ca.target_type='FACILITY'     AND ca.target_id = d.facility_id)
            OR (ca.target_type='DEVICE_GROUP' AND ca.target_id = d.device_group_id) )
         AND NOT EXISTS (SELECT 1 FROM content_assignment_exclusion x
                          WHERE x.assignment_id = ca.id AND x.device_id = d.id)
       ORDER BY ca.priority DESC, ca.id DESC FETCH FIRST 1 ROW ONLY) AS active_playlist_id,

    -- (2) that winning playlist's name (same predicate, joined to playlist)
    (SELECT p.name FROM content_assignment ca JOIN playlist p ON p.id = ca.playlist_id
       WHERE ca.deleted_at IS NULL AND ca.status = 'CONFIRMED'
         AND ca.start_time <= CURRENT_TIMESTAMP AND ca.end_time > CURRENT_TIMESTAMP
         AND ( (ca.target_type='REGION'       AND ca.target_id = d.region_id)
            OR (ca.target_type='FACILITY'     AND ca.target_id = d.facility_id)
            OR (ca.target_type='DEVICE_GROUP' AND ca.target_id = d.device_group_id) )
         AND NOT EXISTS (SELECT 1 FROM content_assignment_exclusion x
                          WHERE x.assignment_id = ca.id AND x.device_id = d.id)
       ORDER BY ca.priority DESC, ca.id DESC FETCH FIRST 1 ROW ONLY) AS active_playlist_name,

    -- (3) computed_status — NOW CONFIRMED + exclusion-aware (fixes the V15/V16 NO_CONTENT gap)
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
