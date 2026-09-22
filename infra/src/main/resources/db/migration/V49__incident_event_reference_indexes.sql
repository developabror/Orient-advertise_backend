-- DATA-03: event retention now skips every event an incident references (first_event_id /
-- last_event_id), whatever the incident's status. That NOT EXISTS probe, and the FK check Postgres
-- runs for every deleted event row, both look incidents up by these columns, which had no index.
CREATE INDEX idx_incident_first_event ON incident (first_event_id);
CREATE INDEX idx_incident_last_event ON incident (last_event_id);
