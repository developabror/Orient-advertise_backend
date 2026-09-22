package uz.orientadvertise.services.infra.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The Postgres-only behaviour H2 cannot show: {@code V51} (a deferrable constraint, a no-op on H2)
 * and {@code V50}'s constraint lookup against Postgres' own naming. Opt-in — it needs a real,
 * disposable Postgres and <b>wipes</b> it:
 *
 * <pre>
 * docker run -d --rm --name orient-pg-smoke -p 127.0.0.1:55432:5432 \
 *   -e POSTGRES_PASSWORD=smoke -e POSTGRES_DB=orient_migration_smoke postgres:17-alpine
 * ORIENT_PG_TEST_URL=jdbc:postgresql://127.0.0.1:55432/orient_migration_smoke \
 *   ./gradlew :infra:test --tests '*PostgresMigrationSmokeTest'
 * </pre>
 *
 * The database must be named {@value #REQUIRED_DB}, so a mistyped URL can never clean a real one.
 */
@EnabledIfEnvironmentVariable(named = "ORIENT_PG_TEST_URL", matches = ".+")
class PostgresMigrationSmokeTest {

    private static final String REQUIRED_DB = "orient_migration_smoke";
    private static final String URL = System.getenv("ORIENT_PG_TEST_URL");
    private static final String USER = System.getenv().getOrDefault("ORIENT_PG_TEST_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("ORIENT_PG_TEST_PASSWORD", "smoke");

    @BeforeEach
    void cleanDatabase() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); var rs = s.executeQuery("SELECT current_database()")) {
            rs.next();
            if (!REQUIRED_DB.equals(rs.getString(1))) {
                throw new IllegalStateException("Refusing to clean '" + rs.getString(1) + "': not " + REQUIRED_DB);
            }
        }
        flyway(null).clean();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static void seedProjectAndRegion(Statement s) throws SQLException {
        s.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (900, 'Smoke', now(), now())");
        s.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (900, 900, 'Smoke', 'SM', now(), now())");
    }

    /**
     * LOGIC-09. The rows are inserted so the heap holds position 2 before position 1 — what a
     * reorder leaves behind — and index scans are off so the UPDATE visits them in heap order.
     * Removing position 0 and compacting then moves 2 → 1 while 1 is still taken.
     */
    private static void compactAfterRemovingTheFirstItem(Statement s) throws SQLException {
        s.execute("INSERT INTO content_file (id, project_id, name, content_type, size_bytes, storage_key) "
                + "VALUES (900, 900, 'Smoke clip', 'video/mp4', 1, 'smoke-key')");
        s.execute("INSERT INTO playlist (id, project_id, name, created_at, updated_at) VALUES (900, 900, 'Smoke', now(), now())");
        s.execute("INSERT INTO playlist_item (id, playlist_id, content_file_id, position) VALUES (901, 900, 900, 0)");
        s.execute("INSERT INTO playlist_item (id, playlist_id, content_file_id, position) VALUES (902, 900, 900, 2)");
        s.execute("INSERT INTO playlist_item (id, playlist_id, content_file_id, position) VALUES (903, 900, 900, 1)");
        s.execute("SET enable_indexscan = off");
        s.execute("SET enable_bitmapscan = off");
        s.execute("DELETE FROM playlist_item WHERE id = 901");
        s.execute("UPDATE playlist_item SET position = position - 1 WHERE playlist_id = 900 AND position > 0");
    }

    @Test
    void logic09_beforeV51_theShiftCollides_afterV51_itSucceeds() throws SQLException {
        flyway("50").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            seedProjectAndRegion(s);
            assertThrows(SQLException.class, () -> compactAfterRemovingTheFirstItem(s),
                    "the pre-V51 constraint should reproduce the review's duplicate-key failure");
        }

        flyway("50").clean();
        flyway(null).migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            seedProjectAndRegion(s);
            assertDoesNotThrow(() -> compactAfterRemovingTheFirstItem(s));
            try (var rs = s.executeQuery("SELECT string_agg(id || ':' || position, ',' ORDER BY position) "
                    + "FROM playlist_item WHERE playlist_id = 900")) {
                rs.next();
                assertEquals("903:0,902:1", rs.getString(1));
            }
            // Still unique at the end of every statement.
            assertThrows(SQLException.class, () -> s.execute("UPDATE playlist_item SET position = 0 WHERE id = 902"));
        }
    }

    @Test
    void g6_onPostgres_theOldConstraintIsGone_andOnlyLiveSerialsAreUnique() throws SQLException {
        flyway(null).migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            try (var rs = s.executeQuery("SELECT count(*) FROM pg_constraint WHERE conname = 'device_serial_number_key'")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "V50 must drop Postgres' device_serial_number_key");
            }
            seedProjectAndRegion(s);
            s.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at, deleted_at) "
                    + "VALUES (901, 900, 'SN-SMOKE', 'Old', 'OFFLINE', now(), now(), now())");
            s.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) "
                    + "VALUES (902, 900, 'SN-SMOKE', 'New', 'ONLINE', now(), now())");
            assertThrows(SQLException.class, () -> s.execute(
                    "INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) "
                            + "VALUES (903, 900, 'SN-SMOKE', 'Dup', 'ONLINE', now(), now())"));
        }
    }
}
