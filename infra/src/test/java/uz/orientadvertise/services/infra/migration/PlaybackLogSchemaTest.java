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
class PlaybackLogSchemaTest {

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
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (700, 'PBProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (700, 700, 'R', 'r', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) VALUES (700, 700, 'SN-PB1', 'D1', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) VALUES (701, 700, 'SN-PB2', 'D2', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO content_file (id, project_id, name, content_type, size_bytes, storage_key, created_at, updated_at) VALUES (700, 700, 'v.mp4', 'video/mp4', 100, 'k', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO content_file (id, project_id, name, content_type, size_bytes, storage_key, created_at, updated_at) VALUES (701, 700, 'i.png', 'image/png', 50, 'k2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    @Test
    void duplicatePlayback_sameDeviceContentTime_blocked() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO playback_log (device_id, content_file_id, played_at, reported_at) " +
                    "VALUES (700, 700, '2025-06-01 10:00:00', CURRENT_TIMESTAMP)");
        }

        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO playback_log (device_id, content_file_id, played_at, reported_at) " +
                        "VALUES (700, 700, '2025-06-01 10:00:00', CURRENT_TIMESTAMP)");
            }
        }, "Exact duplicate (device, content, played_at) must be blocked");
    }

    @Test
    void sameDeviceContent_differentTime_allowed() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO playback_log (device_id, content_file_id, played_at, reported_at) " +
                    "VALUES (700, 700, '2025-06-01 10:00:00', CURRENT_TIMESTAMP)");
        }

        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO playback_log (device_id, content_file_id, played_at, reported_at) " +
                        "VALUES (700, 700, '2025-06-01 10:05:00', CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void sameTimeDifferentDevice_allowed() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO playback_log (device_id, content_file_id, played_at, reported_at) " +
                    "VALUES (700, 700, '2025-06-01 12:00:00', CURRENT_TIMESTAMP)");
        }

        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO playback_log (device_id, content_file_id, played_at, reported_at) " +
                        "VALUES (701, 700, '2025-06-01 12:00:00', CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void sameTimeDifferentContent_allowed() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO playback_log (device_id, content_file_id, played_at, reported_at) " +
                    "VALUES (700, 700, '2025-06-01 14:00:00', CURRENT_TIMESTAMP)");
        }

        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO playback_log (device_id, content_file_id, played_at, reported_at) " +
                        "VALUES (700, 701, '2025-06-01 14:00:00', CURRENT_TIMESTAMP)");
            }
        });
    }
}
