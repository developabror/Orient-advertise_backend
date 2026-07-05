-- Rebind device_group from region to project.
-- A DeviceGroup now belongs to a Project (and may span any region within that project);
-- a Device is unchanged (still region_id NOT NULL + optional device_group_id).
-- Ordering below is load-bearing: add nullable -> backfill -> resolve collisions ->
-- NOT NULL -> FK -> unique/index -> drop the old region binding.

-- 1) Add nullable first so we can backfill before NOT NULL.
ALTER TABLE device_group ADD COLUMN project_id BIGINT;

-- 2) Backfill from the owning region's project.
UPDATE device_group dg SET project_id = (SELECT r.project_id FROM region r WHERE r.id = dg.region_id);

-- 3) COLLISION RESOLUTION (must run BEFORE the unique constraint in step 6).
--    Two same-named groups in different regions of ONE project collapse onto the same
--    (project_id,name) and would violate uq_device_group_name_per_project.
--    Keep the lowest-id row's name; suffix every other duplicate with " #<id>".
--    Dedupe across ALL rows (NO deleted_at filter): uq_device_group_name_per_project (step 6)
--    is a PLAIN full-table unique (same as V5's region constraint), so soft-deleted rows also
--    occupy (project_id,name) and must be deduped too. Otherwise a soft-deleted 'Lobby' in
--    region R1 + an active 'Lobby' in R2 of the SAME project both land on (project,'Lobby')
--    after backfill and the constraint ADD aborts the migration.
--    Use " #<id>" (NOT " (<id>)") — the id is globally unique so " #<id>" cannot re-collide
--    with a pre-existing literal name, and a paren-form like "Lobby (5)" CAN collide with an
--    existing row literally named "Lobby (5)".
--    name is VARCHAR(200); the base name is capped at 100 (@Size(max=100) on
--    CreateDeviceGroupRequest), so " #" + up to ~19 id digits stays well under 200.
UPDATE device_group dg
   SET name = dg.name || ' #' || dg.id
 WHERE dg.id NOT IN (
        SELECT MIN(o.id) FROM device_group o
         GROUP BY o.project_id, o.name);

-- 4) Enforce NOT NULL.
ALTER TABLE device_group ALTER COLUMN project_id SET NOT NULL;

-- 5) FK to project.
ALTER TABLE device_group ADD CONSTRAINT fk_device_group_project
    FOREIGN KEY (project_id) REFERENCES project(id);

-- 6) New unique + supporting index.
ALTER TABLE device_group ADD CONSTRAINT uq_device_group_name_per_project UNIQUE (project_id, name);
CREATE INDEX idx_device_group_project ON device_group (project_id);

-- 7) Drop the old region binding. Order is load-bearing on H2: the FK fk_device_group_region
--    is backed by idx_device_group_region, and H2 (error 90085) refuses to DROP an index that
--    still belongs to a constraint — so the constraints MUST be dropped BEFORE the index, then
--    the column last. (Postgres accepts this order too.) IF EXISTS keeps it idempotent.
ALTER TABLE device_group DROP CONSTRAINT IF EXISTS uq_device_group_name_per_region;
ALTER TABLE device_group DROP CONSTRAINT IF EXISTS fk_device_group_region;
DROP INDEX IF EXISTS idx_device_group_region;
ALTER TABLE device_group DROP COLUMN region_id;
