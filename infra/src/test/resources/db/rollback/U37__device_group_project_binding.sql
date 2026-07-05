-- LOSSY/CONDITIONAL down-migration. project->region is one-to-many, so the original region_id
-- cannot be recovered; we best-effort pick MIN(region.id) in the project. Only safe immediately
-- after V37 with no intervening writes. May ABORT on data that exercised the multi-region span.
ALTER TABLE device_group ADD COLUMN region_id BIGINT;
UPDATE device_group dg SET region_id = (SELECT MIN(r.id) FROM region r WHERE r.project_id = dg.project_id);
-- EDGE (adversarial-review finding): a project with ZERO regions yields NULL here and the next
-- statement fails. U37 requires every project referenced by a group to have >= 1 region.
ALTER TABLE device_group ALTER COLUMN region_id SET NOT NULL;
ALTER TABLE device_group ADD CONSTRAINT fk_device_group_region FOREIGN KEY (region_id) REFERENCES region(id);
-- EDGE: re-adding uq_device_group_name_per_region after collapsing many regions onto MIN(region)
-- WILL collide for any project that had two same-named groups in different regions. Re-run the
-- " #<id>" dedupe keyed on (region_id,name) BEFORE the unique ADD so the constraint can re-attach.
UPDATE device_group dg
   SET name = dg.name || ' #' || dg.id
 WHERE dg.id NOT IN (
        SELECT MIN(o.id) FROM device_group o
         GROUP BY o.region_id, o.name);
ALTER TABLE device_group ADD CONSTRAINT uq_device_group_name_per_region UNIQUE (region_id, name);
CREATE INDEX idx_device_group_region ON device_group (region_id);
-- Drop the project binding. Constraints BEFORE the index (H2 90085: idx_device_group_project
-- backs fk_device_group_project), column last.
ALTER TABLE device_group DROP CONSTRAINT IF EXISTS uq_device_group_name_per_project;
ALTER TABLE device_group DROP CONSTRAINT IF EXISTS fk_device_group_project;
DROP INDEX IF EXISTS idx_device_group_project;
ALTER TABLE device_group DROP COLUMN project_id;
