package uz.orientadvertise.services.infra.migration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StreamUtils;
import uz.orientadvertise.services.infra.TestApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class FlywayRollbackTest {

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
    }

    @Test
    void rollback_V1_dropsHealthCheckLogTable() throws Exception {
        applyMigration("V1__create_health_check_log_table.sql");
        assertTrue(tableExists("health_check_log"), "Table should exist after V1 migration");

        applyRollback("U1__create_health_check_log_table.sql");
        assertFalse(tableExists("health_check_log"), "Table should not exist after U1 rollback");
    }

    @Test
    void rollback_V2_dropsIndex() throws Exception {
        applyMigration("V1__create_health_check_log_table.sql");
        applyMigration("V2__add_index_on_checked_at.sql");
        assertTrue(indexExists("idx_health_check_log_checked_at"), "Index should exist after V2 migration");

        applyRollback("U2__add_index_on_checked_at.sql");
        assertFalse(indexExists("idx_health_check_log_checked_at"), "Index should not exist after U2 rollback");
    }

    @Test
    void fullRollback_revertsAllMigrations() throws Exception {
        applyMigration("V1__create_health_check_log_table.sql");
        applyMigration("V2__add_index_on_checked_at.sql");

        applyRollback("U2__add_index_on_checked_at.sql");
        applyRollback("U1__create_health_check_log_table.sql");

        assertFalse(tableExists("health_check_log"), "All tables should be dropped after full rollback");
    }

    /**
     * Backfill coverage for V37 (§8). The normal migration suite clean+migrates an EMPTY
     * device_group table, so the V37 backfill UPDATE never runs on real rows. Here we migrate
     * only to V35 (the last migration the H2 test harness applies cleanly before V37 — V36 is a
     * Postgres-only pgcrypto migration that never touches device_group, so the pre-V37 device_group
     * schema is identical at V35 and V36), insert a project + region + a region-bound device_group,
     * then apply ONLY V37 and prove the backfill copied the region's project onto
     * device_group.project_id and that region_id is gone.
     */
    @Test
    void backfill_V37_copiesRegionProjectOntoDeviceGroupAndDropsRegionId() throws Exception {
        migrateToVersion("35");   // last pre-V37 migration the H2 harness runs cleanly (V36 is Postgres-only pgcrypto, and never touches device_group)
        assertTrue(columnExists("DEVICE_GROUP", "REGION_ID"), "region_id must still exist pre-V37 (V35)");
        assertFalse(columnExists("DEVICE_GROUP", "PROJECT_ID"), "project_id must not exist yet pre-V37 (V35)");

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) "
                    + "VALUES (700, 'Backfill Project', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                    + "VALUES (701, 700, 'Backfill Region', 'bf701', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // PRE-V37 schema: device_group is region-bound (region_id NOT NULL, no project_id).
            stmt.execute("INSERT INTO device_group (id, region_id, name, created_at, updated_at) "
                    + "VALUES (702, 701, 'Backfill Group', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        applyMigration("V37__device_group_project_binding.sql");

        // project_id was backfilled from the owning region's project (700).
        assertTrue(columnExists("DEVICE_GROUP", "PROJECT_ID"), "project_id must exist after V37");
        assertEquals(700L, scalarLong("SELECT project_id FROM device_group WHERE id = 702"),
                "V37 backfill must copy region.project_id onto device_group.project_id");
        // the old region binding is gone.
        assertFalse(columnExists("DEVICE_GROUP", "REGION_ID"),
                "region_id column must be dropped by V37");
    }

    /**
     * Paired rollback (§8 optional): after V37, applying U37 best-effort restores region_id
     * (picking MIN(region.id) in the project) and removes project_id. Only safe immediately after
     * V37 with no intervening writes — which is exactly this scenario.
     */
    @Test
    void rollback_V37_restoresRegionIdAndDropsProjectId() throws Exception {
        migrateToVersion("35");   // last pre-V37 migration the H2 harness runs cleanly (V36 is Postgres-only pgcrypto, and never touches device_group)
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) "
                    + "VALUES (710, 'Rollback Project', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                    + "VALUES (711, 710, 'Rollback Region', 'rb711', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device_group (id, region_id, name, created_at, updated_at) "
                    + "VALUES (712, 711, 'Rollback Group', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
        applyMigration("V37__device_group_project_binding.sql");

        applyRollback("U37__device_group_project_binding.sql");

        assertTrue(columnExists("DEVICE_GROUP", "REGION_ID"), "U37 must restore region_id");
        assertFalse(columnExists("DEVICE_GROUP", "PROJECT_ID"), "U37 must drop project_id");
        assertEquals(711L, scalarLong("SELECT region_id FROM device_group WHERE id = 712"),
                "U37 must restore the (only) region of the project as region_id");
    }

    /**
     * Regression for the V37 collision-resolution edge: a SOFT-DELETED group and an ACTIVE group
     * sharing a name across two regions of the SAME project both collapse onto (project_id,name)
     * after backfill. uq_device_group_name_per_project is a plain full-table unique (incl. deleted
     * rows), so the step-3 dedupe MUST run across all rows — otherwise the constraint ADD aborts the
     * whole migration in prod. Here the active row (lower id) keeps its base name and the soft-deleted
     * duplicate is suffixed " #<id>"; V37 completes.
     */
    @Test
    void backfill_V37_dedupesSoftDeletedDuplicate_doesNotAbort() throws Exception {
        migrateToVersion("35");
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) "
                    + "VALUES (720, 'Dedupe Project', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                    + "VALUES (721, 720, 'Region One', 'dd721', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                    + "VALUES (722, 720, 'Region Two', 'dd722', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // Active 'Lobby' in region 721 (lower id → keeps the name) ...
            stmt.execute("INSERT INTO device_group (id, region_id, name, created_at, updated_at) "
                    + "VALUES (802, 721, 'Lobby', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // ... and a SOFT-DELETED 'Lobby' in region 722 of the same project (the pre-fix hazard).
            stmt.execute("INSERT INTO device_group (id, region_id, name, created_at, updated_at, deleted_at) "
                    + "VALUES (803, 722, 'Lobby', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        // Must NOT throw — pre-fix this aborted at the unique-constraint ADD (step 6).
        applyMigration("V37__device_group_project_binding.sql");

        assertEquals(2L, scalarLong("SELECT COUNT(*) FROM device_group WHERE project_id = 720"),
                "both rows backfilled onto the project");
        assertEquals(1L, scalarLong(
                "SELECT COUNT(*) FROM device_group WHERE id = 802 AND name = 'Lobby'"),
                "active (lowest-id) row keeps its base name");
        assertEquals(1L, scalarLong(
                "SELECT COUNT(*) FROM device_group WHERE id = 803 AND name = 'Lobby #803'"),
                "soft-deleted duplicate is suffixed so (project_id,name) stays unique");
    }

    /**
     * U38 rollback (§8): after V38, applying U38 drops the four volume columns and their three CHECK
     * constraints. CHECK constraints have no backing index, so drop-constraints-then-columns is safe
     * on H2 (no 90085 ordering hazard); the IF EXISTS clauses make it idempotent.
     */
    @Test
    void rollback_V38_dropsVolumeColumnsAndConstraints() throws Exception {
        migrateToVersion("38");
        assertTrue(columnExists("DEVICE", "DESIRED_VOLUME"), "V38 adds device.desired_volume");
        assertTrue(columnExists("DEVICE", "REPORTED_VOLUME"), "V38 adds device.reported_volume");
        assertTrue(columnExists("DEVICE", "VOLUME_REPORTED_AT"), "V38 adds device.volume_reported_at");
        assertTrue(columnExists("DEVICE_GROUP", "VOLUME"), "V38 adds device_group.volume");

        applyRollback("U38__device_volume_control.sql");

        assertFalse(columnExists("DEVICE", "DESIRED_VOLUME"), "U38 must drop device.desired_volume");
        assertFalse(columnExists("DEVICE", "REPORTED_VOLUME"), "U38 must drop device.reported_volume");
        assertFalse(columnExists("DEVICE", "VOLUME_REPORTED_AT"), "U38 must drop device.volume_reported_at");
        assertFalse(columnExists("DEVICE_GROUP", "VOLUME"), "U38 must drop device_group.volume");
    }

    private void migrateToVersion(String target) {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .cleanDisabled(false)
                .load();
        flyway.migrate();
    }

    private void applyMigration(String filename) throws IOException, SQLException {
        executeSqlFile("db/migration/" + filename);
    }

    private void applyRollback(String filename) throws IOException, SQLException {
        executeSqlFile("db/rollback/" + filename);
    }

    private void executeSqlFile(String path) throws IOException, SQLException {
        var resource = new ClassPathResource(path);
        var sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private boolean columnExists(String tableName, String columnName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getColumns(null, null, tableName.toUpperCase(), columnName.toUpperCase())) {
            return rs.next();
        }
    }

    private long scalarLong(String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next(), "query returned no rows: " + sql);
            return rs.getLong(1);
        }
    }

    private boolean tableExists(String tableName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getTables(null, null, tableName.toUpperCase(), null)) {
            return rs.next();
        }
    }

    private boolean indexExists(String indexName) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             ResultSet rs = conn.getMetaData().getIndexInfo(null, null, "HEALTH_CHECK_LOG", false, false)) {
            while (rs.next()) {
                if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
