DROP VIEW IF EXISTS device_status_view;
-- Recreate the V15 shape (without facility_name)
CREATE VIEW device_status_view AS
SELECT
    d.id,
    d.region_id,
    d.facility_id,
    d.device_group_id,
    d.serial_number,
    d.name,
    d.last_heartbeat_at,
    d.created_at,
    d.updated_at,
    CASE
        WHEN d.last_heartbeat_at IS NULL THEN 'OFFLINE'
        WHEN d.last_heartbeat_at < CURRENT_TIMESTAMP - INTERVAL '16' MINUTE THEN 'OFFLINE'
        WHEN NOT EXISTS (
            SELECT 1 FROM content_assignment ca
            WHERE ca.deleted_at IS NULL
              AND ca.start_time <= CURRENT_TIMESTAMP
              AND ca.end_time > CURRENT_TIMESTAMP
              AND (
                  (ca.target_type = 'REGION' AND ca.target_id = d.region_id)
                  OR (ca.target_type = 'FACILITY' AND ca.target_id = d.facility_id)
                  OR (ca.target_type = 'DEVICE_GROUP' AND ca.target_id = d.device_group_id)
              )
        ) THEN 'NO_CONTENT'
        ELSE 'ONLINE'
    END AS computed_status
FROM device d
WHERE d.deleted_at IS NULL;
