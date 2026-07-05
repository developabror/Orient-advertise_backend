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
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class RemoteActionSchemaTest {

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
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (600, 'RAProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (600, 600, 'R', 'r', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) VALUES (600, 600, 'SN-RA', 'D1', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    @Test
    void remoteAction_statusMustBeValid() {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO remote_action (device_id, action_type, status, issued_by, issued_at, expires_at, updated_at) " +
                        "VALUES (600, 'REBOOT', 'INVALID', 'admin', CURRENT_TIMESTAMP, TIMESTAMPADD(MINUTE, 5, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void remoteAction_expiresAtMustBeAfterIssuedAt() {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO remote_action (device_id, action_type, status, issued_by, issued_at, expires_at, updated_at) " +
                        "VALUES (600, 'REBOOT', 'PENDING', 'admin', '2025-06-01 10:00:00', '2025-06-01 09:00:00', CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void remoteAction_validInsert_succeeds() {
        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO remote_action (device_id, action_type, status, issued_by, issued_at, expires_at, updated_at) " +
                        "VALUES (600, 'REBOOT', 'PENDING', 'admin', CURRENT_TIMESTAMP, TIMESTAMPADD(MINUTE, 5, CURRENT_TIMESTAMP), CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void entityAuditLog_actionMustBeValid() {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO entity_audit_log (entity_type, entity_id, action, changed_by, changed_at) " +
                        "VALUES ('Device', 600, 'INVALID', 'admin', CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void entityAuditLog_storesOldNewValues() {
        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO entity_audit_log (entity_type, entity_id, action, changed_by, old_value, new_value, changed_at) " +
                        "VALUES ('Device', 600, 'UPDATE', 'admin', " +
                        "'{\"status\":\"OFFLINE\"}', '{\"status\":\"ONLINE\"}', CURRENT_TIMESTAMP)");
            }
        });

        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection();
                 var rs = conn.createStatement().executeQuery(
                         "SELECT old_value, new_value FROM entity_audit_log WHERE entity_type = 'Device' AND entity_id = 600")) {
                rs.next();
                assert rs.getString("old_value").contains("OFFLINE");
                assert rs.getString("new_value").contains("ONLINE");
            }
        });
    }
}
