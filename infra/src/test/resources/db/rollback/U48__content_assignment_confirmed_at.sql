-- Rollback of V48 (assignment precedence by confirmed_at).
--
-- Two steps, in this order:
--   1. Restore device_status_view to its V41 definition — the view SELECTs confirmed_at, so the
--      column cannot be dropped while it references it (PostgreSQL refuses with a dependency
--      error; H2 leaves a broken view).
--   2. Drop the column.
--
-- Reverting the ORDER BY re-introduces the pre-V48 tie-break (priority DESC, id DESC), which is
-- what the Java/JPQL copies read like before V48 too — roll all four back together.
DROP VIEW IF EXISTS device_status_view;
CREATE VIEW device_status_view AS
SELECT
    d.id, d.region_id, d.facility_id, f.name AS facility_name, d.device_group_id, d.sync_group_id,
    d.serial_number, d.name, d.last_heartbeat_at, d.created_at, d.updated_at,

    (SELECT ca.playlist_id FROM content_assignment ca
       WHERE ca.deleted_at IS NULL AND ca.status = 'CONFIRMED'
         AND ca.start_time <= CURRENT_TIMESTAMP AND ca.end_time > CURRENT_TIMESTAMP
         AND ( (ca.target_type='REGION'       AND ca.target_id = d.region_id)
            OR (ca.target_type='FACILITY'     AND ca.target_id = d.facility_id)
            OR (ca.target_type='DEVICE_GROUP' AND ca.target_id = d.device_group_id) )
         AND NOT EXISTS (SELECT 1 FROM content_assignment_exclusion x
                          WHERE x.assignment_id = ca.id AND x.device_id = d.id)
       ORDER BY ca.priority DESC, ca.id DESC FETCH FIRST 1 ROW ONLY) AS active_playlist_id,

    (SELECT p.name FROM content_assignment ca JOIN playlist p ON p.id = ca.playlist_id
       WHERE ca.deleted_at IS NULL AND ca.status = 'CONFIRMED'
         AND ca.start_time <= CURRENT_TIMESTAMP AND ca.end_time > CURRENT_TIMESTAMP
         AND ( (ca.target_type='REGION'       AND ca.target_id = d.region_id)
            OR (ca.target_type='FACILITY'     AND ca.target_id = d.facility_id)
            OR (ca.target_type='DEVICE_GROUP' AND ca.target_id = d.device_group_id) )
         AND NOT EXISTS (SELECT 1 FROM content_assignment_exclusion x
                          WHERE x.assignment_id = ca.id AND x.device_id = d.id)
       ORDER BY ca.priority DESC, ca.id DESC FETCH FIRST 1 ROW ONLY) AS active_playlist_name,

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

ALTER TABLE content_assignment DROP COLUMN confirmed_at;
