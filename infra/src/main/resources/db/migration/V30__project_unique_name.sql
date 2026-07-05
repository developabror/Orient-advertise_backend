-- V30: Enforce UNIQUE on project.name.
--
-- Why: the V4 migration that introduced the project table did not constrain name to be
-- unique. Region/Facility/DeviceGroup/Playlist were all later given UNIQUE(parent_id,
-- name) constraints, but Project sits at the root and has no parent — its uniqueness
-- key is just the name. The Project CRUD endpoint (POST /api/projects) needs a clean
-- 409 path on duplicate creation; the app-level pre-check in ProjectManagementService
-- is the user-visible 409, this constraint is the safety net.
--
-- This migration assumes no duplicate names exist. If it fails on an existing dataset,
-- the operator must dedupe manually before retrying — automating dedupe here would
-- silently merge or rename rows operators may want to inspect first.

ALTER TABLE project
    ADD CONSTRAINT uq_project_name UNIQUE (name);
