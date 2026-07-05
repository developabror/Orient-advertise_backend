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
class EventIncidentSchemaTest {

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
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) VALUES (500, 'EvtProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) VALUES (500, 500, 'R', 'r', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) VALUES (500, 500, 'SN-EVT', 'D1', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    @Test
    void event_priorityMustBeValid() {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO event (device_id, event_type, priority, occurred_at, created_at) " +
                        "VALUES (500, 'TEST', 'INVALID', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void event_allPrioritiesAccepted() {
        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO event (id, device_id, event_type, priority, occurred_at, created_at) VALUES (1, 500, 'T', 'CRITICAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO event (id, device_id, event_type, priority, occurred_at, created_at) VALUES (2, 500, 'T', 'HIGH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO event (id, device_id, event_type, priority, occurred_at, created_at) VALUES (3, 500, 'T', 'MEDIUM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO event (id, device_id, event_type, priority, occurred_at, created_at) VALUES (4, 500, 'T', 'LOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO event (id, device_id, event_type, priority, occurred_at, created_at) VALUES (5, 500, 'T', 'INFO', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void incident_statusMustBeValid() {
        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO event (id, device_id, event_type, priority, occurred_at, created_at) VALUES (10, 500, 'T', 'HIGH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO incident (device_id, event_type, status, priority, occurrence_count, first_event_id, last_event_id, opened_at, updated_at) " +
                        "VALUES (500, 'T', 'OPEN', 'HIGH', 1, 10, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        });

        assertThrows(SQLException.class, () -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO event (id, device_id, event_type, priority, occurred_at, created_at) VALUES (11, 500, 'T', 'HIGH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO incident (device_id, event_type, status, priority, occurrence_count, first_event_id, last_event_id, opened_at, updated_at) " +
                        "VALUES (500, 'T', 'INVALID', 'HIGH', 1, 11, 11, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            }
        });
    }

    @Test
    void incident_lifecycleStatusesValid() {
        assertDoesNotThrow(() -> {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO event (id, device_id, event_type, priority, occurred_at, created_at) VALUES (20, 500, 'L', 'LOW', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                stmt.execute("INSERT INTO incident (id, device_id, event_type, status, priority, occurrence_count, first_event_id, last_event_id, opened_at, updated_at) " +
                        "VALUES (20, 500, 'L', 'OPEN', 'LOW', 1, 20, 20, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                stmt.execute("UPDATE incident SET status = 'ACKNOWLEDGED', acknowledged_at = CURRENT_TIMESTAMP WHERE id = 20");
                stmt.execute("UPDATE incident SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP WHERE id = 20");
            }
        });
    }
}
