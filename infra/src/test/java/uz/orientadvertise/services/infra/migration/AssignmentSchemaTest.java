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
}
