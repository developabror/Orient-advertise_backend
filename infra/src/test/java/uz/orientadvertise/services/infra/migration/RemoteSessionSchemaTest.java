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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V43 {@code remote_session} + the six {@code device} capability columns.
 *
 * <p>Mirrors {@link RemoteActionSchemaTest}. The whole V1..V43 chain is applied against H2 in
 * PostgreSQL mode, which is the point: the DDL has to be valid on <b>both</b> H2 (tests) and
 * PostgreSQL (production), and this is where a Postgres-only construct — a partial unique index,
 * for instance — would be caught before it reached a deploy.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class RemoteSessionSchemaTest {

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
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (700, 'RSProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (700, 700, 'R', 'r700', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) VALUES (700, 700, 'SN-RS', 'D700', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) VALUES (701, 700, 'SN-RS2', 'D701', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    private void exec(String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private static String insert(String sessionKey, String status, String deviceId, String expiresAt) {
        return ("INSERT INTO remote_session (session_key, device_id, status, view_only, issued_by, "
                + "issued_at, expires_at, updated_at) VALUES ('%s', %s, '%s', FALSE, 'admin', "
                + "CURRENT_TIMESTAMP, %s, CURRENT_TIMESTAMP)")
                .formatted(sessionKey, deviceId, status, expiresAt);
    }

    private static final String IN_30_MIN = "TIMESTAMPADD(MINUTE, 30, CURRENT_TIMESTAMP)";

    // ----- positive -----

    @Test
    void remoteSession_validInsert_succeeds() {
        assertDoesNotThrow(() -> exec(insert("rs_aaaa1111", "PENDING", "700", IN_30_MIN)));
    }

    @Test
    void remoteSession_everyDeclaredStatus_isAccepted() {
        assertDoesNotThrow(() -> {
            exec(insert("rs_s1", "PENDING", "700", IN_30_MIN));
            exec(insert("rs_s2", "ACTIVE", "700", IN_30_MIN));
            exec(insert("rs_s3", "ENDED", "700", IN_30_MIN));
            exec(insert("rs_s4", "FAILED", "700", IN_30_MIN));
            exec(insert("rs_s5", "EXPIRED", "700", IN_30_MIN));
        });
    }

    @Test
    void remoteSession_statusDefaultsToPending() throws Exception {
        exec("INSERT INTO remote_session (session_key, device_id, issued_by, issued_at, expires_at, updated_at) "
                + "VALUES ('rs_default', 700, 'admin', CURRENT_TIMESTAMP, " + IN_30_MIN + ", CURRENT_TIMESTAMP)");

        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT status, view_only FROM remote_session WHERE session_key = 'rs_default'")) {
            assertTrue(rs.next());
            assertEquals("PENDING", rs.getString("status"));
            assertEquals(false, rs.getBoolean("view_only"), "view_only defaults to FALSE");
        }
    }

    @Test
    void remoteSession_storesTheFullLifecycleColumns() throws Exception {
        exec("INSERT INTO remote_session (session_key, device_id, status, view_only, issued_by, issued_at, "
                + "expires_at, started_at, ended_at, end_reason, error, device_width, device_height, updated_at) "
                + "VALUES ('rs_full', 700, 'ENDED', TRUE, 'operator1', CURRENT_TIMESTAMP, " + IN_30_MIN + ", "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'OPERATOR_STOP', 'none', 1280, 720, CURRENT_TIMESTAMP)");

        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT view_only, end_reason, device_width, device_height FROM remote_session "
                             + "WHERE session_key = 'rs_full'")) {
            assertTrue(rs.next());
            assertEquals(true, rs.getBoolean("view_only"));
            assertEquals("OPERATOR_STOP", rs.getString("end_reason"));
            assertEquals(1280, rs.getInt("device_width"));
            assertEquals(720, rs.getInt("device_height"));
        }
    }

    @Test
    void remoteSession_twoLiveSessionsForOneDevice_areAcceptedByTheSchema() throws Exception {
        // Deliberate: H2 has no partial unique indexes, so "one live session per device" is a
        // SERVICE-layer rule (exactly like remote_action's one-PENDING-per-type). If this ever
        // starts failing, someone added a constraint that will not exist on H2 or will be wrong
        // on Postgres — read V43's header before "fixing" it.
        exec(insert("rs_live1", "PENDING", "700", IN_30_MIN));
        assertDoesNotThrow(() -> exec(insert("rs_live2", "ACTIVE", "700", IN_30_MIN)));
    }

    @Test
    void device_hasAllSixRemoteCapabilityColumns() throws Exception {
        assertDoesNotThrow(() -> exec(
                "UPDATE device SET remote_supported = TRUE, remote_input = 'ROOT', "
                        + "remote_transport = 'SCRCPY_WS', remote_max_width = 1280, "
                        + "remote_max_height = 720, remote_caps_at = CURRENT_TIMESTAMP WHERE id = 700"));

        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT remote_supported, remote_input, remote_transport, remote_max_width, "
                             + "remote_max_height, remote_caps_at FROM device WHERE id = 700")) {
            assertTrue(rs.next());
            assertEquals(true, rs.getBoolean("remote_supported"));
            assertEquals("ROOT", rs.getString("remote_input"));
            assertEquals("SCRCPY_WS", rs.getString("remote_transport"));
            assertEquals(1280, rs.getInt("remote_max_width"));
        }
    }

    @Test
    void device_remoteCapabilityColumnsAreNullableSoNoBackfillIsNeeded() throws Exception {
        // Device 701 was inserted without touching any remote_* column.
        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT remote_supported, remote_caps_at FROM device WHERE id = 701")) {
            assertTrue(rs.next());
            rs.getBoolean("remote_supported");
            assertTrue(rs.wasNull(), "remote_supported must be NULL = never reported");
            assertEquals(null, rs.getTimestamp("remote_caps_at"));
        }
    }

    // ----- upgrade path: V42 database → V43 -----

    @Test
    void v43_appliesCleanlyOnAnExistingV42Database() throws Exception {
        // The real deploy shape: a live database already at V42 receives V43 alone. Distinct from
        // the clean-DB path every other test here exercises — an ALTER that only works on an empty
        // table, or a CREATE that collides with something V42 left behind, shows up only here.
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("42").cleanDisabled(false).load().migrate();
        assertFalse(tableExists("REMOTE_SESSION"), "remote_session must not exist at V42");
        assertFalse(columnExists("DEVICE", "REMOTE_SUPPORTED"), "device.remote_supported must not exist at V42");

        // Seed a row so the ALTERs run against a NON-EMPTY device table — the case where a
        // NOT NULL column without a default would fail. All six are nullable precisely so no
        // backfill is needed.
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (710, 'Up', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (710, 710, 'R', 'r710', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) VALUES (710, 710, 'SN-UP', 'D710', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        assertDoesNotThrow(() -> Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").cleanDisabled(false).load().migrate());

        assertTrue(tableExists("REMOTE_SESSION"));
        assertTrue(columnExists("DEVICE", "REMOTE_SUPPORTED"));
        assertTrue(columnExists("DEVICE", "REMOTE_INPUT"));
        assertTrue(columnExists("DEVICE", "REMOTE_TRANSPORT"));
        assertTrue(columnExists("DEVICE", "REMOTE_MAX_WIDTH"));
        assertTrue(columnExists("DEVICE", "REMOTE_MAX_HEIGHT"));
        assertTrue(columnExists("DEVICE", "REMOTE_CAPS_AT"));

        // The pre-existing row survives with NULL capability — "never reported", no backfill.
        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT remote_supported FROM device WHERE id = 710")) {
            assertTrue(rs.next());
            rs.getBoolean("remote_supported");
            assertTrue(rs.wasNull());
        }
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             var rs = conn.getMetaData().getTables(null, null, table.toUpperCase(java.util.Locale.ROOT), null)) {
            return rs.next();
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             var rs = conn.getMetaData().getColumns(null, null,
                     table.toUpperCase(java.util.Locale.ROOT), column.toUpperCase(java.util.Locale.ROOT))) {
            return rs.next();
        }
    }

    // ----- negative: every constraint in V43 -----

    @Test
    void remoteSession_statusMustBeValid() {
        assertThrows(SQLException.class, () -> exec(insert("rs_bad", "TELEPORTING", "700", IN_30_MIN)));
    }

    @Test
    void remoteSession_expiresAtMustBeAfterIssuedAt() {
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO remote_session (session_key, device_id, status, view_only, issued_by, "
                        + "issued_at, expires_at, updated_at) VALUES ('rs_backwards', 700, 'PENDING', FALSE, "
                        + "'admin', '2026-06-01 10:00:00', '2026-06-01 09:00:00', CURRENT_TIMESTAMP)"));
    }

    @Test
    void remoteSession_sessionKeyMustBeUnique() throws Exception {
        exec(insert("rs_dup", "PENDING", "700", IN_30_MIN));

        assertThrows(SQLException.class, () -> exec(insert("rs_dup", "PENDING", "701", IN_30_MIN)));
    }

    @Test
    void remoteSession_deviceIdMustReferenceARealDevice() {
        assertThrows(SQLException.class, () -> exec(insert("rs_orphan", "PENDING", "999999", IN_30_MIN)));
    }

    @Test
    void remoteSession_sessionKeyIsRequired() {
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO remote_session (device_id, status, view_only, issued_by, issued_at, "
                        + "expires_at, updated_at) VALUES (700, 'PENDING', FALSE, 'admin', "
                        + "CURRENT_TIMESTAMP, " + IN_30_MIN + ", CURRENT_TIMESTAMP)"));
    }

    @Test
    void remoteSession_issuedByIsRequired() {
        assertThrows(SQLException.class, () -> exec(
                "INSERT INTO remote_session (session_key, device_id, status, view_only, issued_at, "
                        + "expires_at, updated_at) VALUES ('rs_noissuer', 700, 'PENDING', FALSE, "
                        + "CURRENT_TIMESTAMP, " + IN_30_MIN + ", CURRENT_TIMESTAMP)"));
    }
}
