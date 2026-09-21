package uz.orientadvertise.services.infra.migration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class AssignmentSchemaTest {

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (300, 'AssignProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (300, 300, 'R', 'r', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO content_file (id, project_id, name, content_type, size_bytes, storage_key, created_at, updated_at) VALUES (300, 300, 'v.mp4', 'video/mp4', 100, 'k', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO playlist (id, project_id, name, created_at, updated_at) VALUES (300, 300, 'PL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) VALUES (300, 300, 'SN-ASG', 'D1', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    @Test
    void endTime_mustBeAfterStartTime() {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO content_assignment (playlist_id, target_type, target_id, priority, start_time, end_time, created_at, updated_at) " +
                        "VALUES (300, 'REGION', 300, 1, '2025-01-02 00:00:00', '2025-01-01 00:00:00', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "end_time must be after start_time");
    }

    @Test
    void targetType_mustBeValid() {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO content_assignment (playlist_id, target_type, target_id, priority, start_time, end_time, created_at, updated_at) " +
                        "VALUES (300, 'INVALID', 300, 1, '2025-01-01 00:00:00', '2025-01-02 00:00:00', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "Only REGION, FACILITY, DEVICE_GROUP are allowed");
    }

    @Test
    void exclusion_uniquePerDevicePerAssignment() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO content_assignment (id, playlist_id, target_type, target_id, priority, start_time, end_time, created_at, updated_at) " +
                    "VALUES (300, 300, 'REGION', 300, 1, '2025-01-01 00:00:00', '2025-02-01 00:00:00', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO content_assignment_exclusion (assignment_id, device_id, created_at) VALUES (300, 300, CURRENT_TIMESTAMP)");
        }

        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO content_assignment_exclusion (assignment_id, device_id, created_at) VALUES (300, 300, CURRENT_TIMESTAMP)");
            }
        }, "Same device excluded twice from same assignment must be blocked");
    }

    @Test
    void exclusion_cascadesOnAssignmentDelete() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO content_assignment (id, playlist_id, target_type, target_id, priority, start_time, end_time, created_at, updated_at) " +
                    "VALUES (301, 300, 'REGION', 300, 1, '2025-03-01 00:00:00', '2025-04-01 00:00:00', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO content_assignment_exclusion (assignment_id, device_id, created_at) VALUES (301, 300, CURRENT_TIMESTAMP)");
        }

        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM content_assignment WHERE id = 301");
            }
        });

        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM content_assignment_exclusion WHERE assignment_id = 301")) {
            rs.next();
            assert rs.getInt(1) == 0 : "Exclusions must cascade delete with assignment";
        }
    }

    // ===== V48: confirmed_at (assignment precedence) =====

    /**
     * The column must exist and be NULLABLE — a DRAFT was never confirmed, and rows written before
     * V48 by a legacy direct insert have no value either. Every reader COALESCEs to created_at, so
     * the absence has to be representable rather than defaulted.
     *
     * <p>{@code ddl-auto: validate} would already fail the whole api test context if the column
     * were missing against {@code ContentAssignment.confirmedAt}; this pins nullability, which
     * validate does not check.
     */
    @Test
    void confirmedAt_exists_andIsNullable() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO content_assignment (id, playlist_id, target_type, target_id, priority, "
                    + "start_time, end_time, status, created_at, updated_at) VALUES "
                    + "(310, 300, 'REGION', 300, 1, '2025-01-01 00:00:00', '2025-02-01 00:00:00', "
                    + "'DRAFT', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT confirmed_at, COALESCE(confirmed_at, created_at) AS eff "
                             + "FROM content_assignment WHERE id = 310")) {
            rs.next();
            assertNull(rs.getTimestamp("confirmed_at"), "a never-confirmed row keeps a NULL confirmed_at");
            assertNotNull(rs.getTimestamp("eff"), "readers COALESCE it to created_at — never NULL");
        }
    }

    /**
     * The precedence instant is writable and orders exactly as the views ORDER BY it: a row
     * confirmed LATER outranks one confirmed earlier at the same priority, whatever the ids say.
     * Asserted through the same COALESCE expression the views use, on the real H2 schema.
     */
    @Test
    void confirmedAt_orderingPutsTheMostRecentlyConfirmedFirst() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            // id 320 is the HIGHER id but was confirmed a day EARLIER than id 319.
            stmt.execute("INSERT INTO content_assignment (id, playlist_id, target_type, target_id, priority, "
                    + "start_time, end_time, status, created_at, updated_at, confirmed_at) VALUES "
                    + "(320, 300, 'REGION', 300, 1, '2025-01-01 00:00:00', '2025-02-01 00:00:00', "
                    + "'CONFIRMED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '2025-01-01 00:00:00')");
            // ...and 319 carries NO confirmed_at, so it falls back to created_at (now) and wins.
            stmt.execute("INSERT INTO content_assignment (id, playlist_id, target_type, target_id, priority, "
                    + "start_time, end_time, status, created_at, updated_at) VALUES "
                    + "(319, 300, 'REGION', 300, 1, '2025-01-01 00:00:00', '2025-02-01 00:00:00', "
                    + "'CONFIRMED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT id FROM content_assignment WHERE id IN (319, 320) "
                             + "ORDER BY priority DESC, COALESCE(confirmed_at, created_at) DESC, id DESC")) {
            rs.next();
            assertEquals(319L, rs.getLong("id"),
                    "the most recently confirmed row sorts first, even with the lower id");
        }
    }

    /**
     * V48's backfill, exercised for real: migrate to V47 (BEFORE the column exists), insert a
     * CONFIRMED row with a past {@code created_at} and a DRAFT row, then migrate to head.
     *
     * <p>Counting over the fully-migrated schema would be worthless — nothing in {@code db/migration}
     * inserts an assignment, so the table is empty and the assertion holds vacuously. The two-phase
     * migration is what makes the UPDATE in V48 the code under test: revert it and this goes red.
     */
    @Test
    void backfill_stampsConfirmedRowsWithCreatedAt_andLeavesDraftsNull() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        // Phase 1: everything up to and including V47 — content_assignment has no confirmed_at yet.
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("47").cleanDisabled(false).load().migrate();

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES "
                    + "(400, 'BackfillProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO playlist (id, project_id, name, created_at, updated_at) VALUES "
                    + "(400, 400, 'PL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // A legacy CONFIRMED row whose created_at is clearly in the past, so "backfilled to
            // created_at" cannot be confused with "defaulted to the migration's clock".
            stmt.execute("INSERT INTO content_assignment (id, playlist_id, target_type, target_id, priority, "
                    + "start_time, end_time, status, created_at, updated_at) VALUES "
                    + "(400, 400, 'REGION', 400, 1, '2025-01-01 00:00:00', '2030-01-01 00:00:00', "
                    + "'CONFIRMED', TIMESTAMP '2025-02-03 04:05:06', TIMESTAMP '2026-09-09 09:09:09')");
            // …and a DRAFT, which was never confirmed and must stay NULL.
            stmt.execute("INSERT INTO content_assignment (id, playlist_id, target_type, target_id, priority, "
                    + "start_time, end_time, status, created_at, updated_at) VALUES "
                    + "(401, 400, 'REGION', 400, 1, '2025-01-01 00:00:00', '2030-01-01 00:00:00', "
                    + "'DRAFT', TIMESTAMP '2025-02-03 04:05:06', CURRENT_TIMESTAMP)");
        }

        // Phase 2: V48 adds the column and backfills.
        flyway.migrate();

        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT id, created_at, confirmed_at FROM content_assignment "
                             + "WHERE id IN (400, 401) ORDER BY id")) {
            rs.next();
            assertEquals(400L, rs.getLong("id"));
            assertNotNull(rs.getTimestamp("confirmed_at"), "the CONFIRMED row must be backfilled");
            assertEquals(rs.getTimestamp("created_at"), rs.getTimestamp("confirmed_at"),
                    "backfilled to created_at — NOT to the migration's own clock, and not to updated_at");
            rs.next();
            assertEquals(401L, rs.getLong("id"));
            assertNull(rs.getTimestamp("confirmed_at"), "a DRAFT was never confirmed — stays NULL");
        }
    }
}
