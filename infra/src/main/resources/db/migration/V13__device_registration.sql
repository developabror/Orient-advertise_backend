-- Device registration: add token and registration tracking to device table.
-- device_token is the credential returned to the TV-Box on first boot.
ALTER TABLE device ADD COLUMN device_token VARCHAR(200);
ALTER TABLE device ADD COLUMN registered_at TIMESTAMP;

CREATE UNIQUE INDEX uq_device_token ON device (device_token);

-- Default region for self-registering devices (unassigned).
-- Devices register themselves without knowing their region yet;
-- they are placed in a default "Unassigned" region.
INSERT INTO project (id, name, created_at, updated_at)
    SELECT -1, 'Default', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    WHERE NOT EXISTS (SELECT 1 FROM project WHERE id = -1);

INSERT INTO region (id, project_id, name, code, created_at, updated_at)
    SELECT -1, -1, 'Unassigned', 'UNASSIGNED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
    WHERE NOT EXISTS (SELECT 1 FROM region WHERE id = -1);
