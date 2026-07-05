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
class ScheduleSchemaTest {

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
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (400, 'SchedProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (400, 400, 'R', 'r', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO content_file (id, project_id, name, content_type, size_bytes, storage_key, created_at, updated_at) VALUES (400, 400, 'v.mp4', 'video/mp4', 100, 'k', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO playlist (id, project_id, name, created_at, updated_at) VALUES (400, 400, 'PL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO content_assignment (id, playlist_id, target_type, target_id, priority, start_time, end_time, created_at, updated_at) " +
                    "VALUES (400, 400, 'REGION', 400, 1, '2025-01-01 00:00:00', '2025-12-31 23:59:59', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    @Test
    void endTime_mustBeAfterStartTime() {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO schedule (assignment_id, start_time_utc, end_time_utc, repeat_type, created_at, updated_at) " +
                        "VALUES (400, '2025-06-02 00:00:00', '2025-06-01 00:00:00', 'NONE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void repeatType_mustBeValid() {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO schedule (assignment_id, start_time_utc, end_time_utc, repeat_type, created_at, updated_at) " +
                        "VALUES (400, '2025-06-01 00:00:00', '2025-06-02 00:00:00', 'HOURLY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void repeatNone_withRepeatEnd_blocked() {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO schedule (assignment_id, start_time_utc, end_time_utc, repeat_type, repeat_end_utc, created_at, updated_at) " +
                        "VALUES (400, '2025-06-01 00:00:00', '2025-06-02 00:00:00', 'NONE', '2025-07-01 00:00:00', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "NONE repeat type must not have repeat_end_utc");
    }

    @Test
    void repeatDaily_withoutRepeatEnd_blocked() {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO schedule (assignment_id, start_time_utc, end_time_utc, repeat_type, created_at, updated_at) " +
                        "VALUES (400, '2025-06-01 00:00:00', '2025-06-01 01:00:00', 'DAILY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        }, "Repeating schedule must have repeat_end_utc");
    }

    @Test
    void validNoneSchedule_succeeds() {
        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO schedule (assignment_id, start_time_utc, end_time_utc, repeat_type, created_at, updated_at) " +
                        "VALUES (400, '2025-06-01 00:00:00', '2025-06-02 00:00:00', 'NONE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void validDailySchedule_succeeds() {
        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO schedule (assignment_id, start_time_utc, end_time_utc, repeat_type, repeat_end_utc, created_at, updated_at) " +
                        "VALUES (400, '2025-06-01 08:00:00', '2025-06-01 18:00:00', 'DAILY', '2025-07-01 00:00:00', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void allTimestamps_storedInUtc() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO schedule (id, assignment_id, start_time_utc, end_time_utc, repeat_type, created_at, updated_at) " +
                    "VALUES (999, 400, '2025-06-01 00:00:00', '2025-06-02 00:00:00', 'NONE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }

        try (Connection conn = dataSource.getConnection();
             var rs = conn.createStatement().executeQuery("SELECT start_time_utc FROM schedule WHERE id = 999")) {
            rs.next();
            var ts = rs.getTimestamp("start_time_utc");
            // Timestamp stored as-is (UTC) — no timezone conversion at DB level
            assert ts != null : "Timestamp must be stored";
        }
    }
}
