package uz.orientadvertise.services.infra.migration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.domain.model.Incident;
import uz.orientadvertise.services.domain.repository.IncidentRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration proof that the operator-facing {@link IncidentRepository} queries exclude
 * incidents belonging to soft-deleted devices — the read-side half of the v1.0.118
 * "deleted devices no longer produce incident reports" fix.
 *
 * <p>Seeds one ACTIVE and one SOFT-DELETED device, each with an OPEN incident, then asserts
 * that {@code findByStatus}, {@code countOpenByPriority} and {@code countOpenedInRange} all
 * see only the active device's incident. Lives in the infra module because it needs
 * {@code @SpringBootTest} + Flyway-migrated tables to exercise the real SQL — a Mockito unit
 * test cannot observe a {@code deletedAt IS NULL} predicate that lives inside the query.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class IncidentDeletedDeviceFilterTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private IncidentRepository incidentRepository;

    @BeforeEach
    void setUp() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        // Device 7001 is active; device 7002 is soft-deleted (deleted_at set). Each owns one
        // OPEN incident opened "now", anchored to its own seed event (first/last_event_id are
        // NOT NULL). The active incident is HIGH, the deleted one CRITICAL, so the priority
        // grouping assertion can prove the deleted bucket vanishes entirely.
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO project (id, name, created_at, updated_at) "
                    + "VALUES (700, 'IncProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                    + "VALUES (700, 700, 'IncRegion', 'IC', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, "
                    + "created_at, updated_at) "
                    + "VALUES (7001, 700, 'SN-ACTIVE', 'Active', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO device (id, region_id, serial_number, name, status, "
                    + "created_at, updated_at, deleted_at) "
                    + "VALUES (7002, 700, 'SN-DELETED', 'Deleted', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

            stmt.execute("INSERT INTO event (id, device_id, event_type, priority, occurred_at, created_at) "
                    + "VALUES (7001, 7001, 'DEVICE_OFFLINE', 'HIGH', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO event (id, device_id, event_type, priority, occurred_at, created_at) "
                    + "VALUES (7002, 7002, 'DEVICE_OFFLINE', 'CRITICAL', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

            stmt.execute("INSERT INTO incident (id, device_id, event_type, status, priority, "
                    + "occurrence_count, first_event_id, last_event_id, opened_at, updated_at) "
                    + "VALUES (7001, 7001, 'DEVICE_OFFLINE', 'OPEN', 'HIGH', 1, 7001, 7001, "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO incident (id, device_id, event_type, status, priority, "
                    + "occurrence_count, first_event_id, last_event_id, opened_at, updated_at) "
                    + "VALUES (7002, 7002, 'DEVICE_OFFLINE', 'OPEN', 'CRITICAL', 1, 7002, 7002, "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    @Test
    void findByStatus_open_excludesSoftDeletedDeviceIncident() {
        var open = incidentRepository.findByStatus(Incident.Status.OPEN);

        assertEquals(1, open.size(),
                "findByStatus(OPEN) must skip the soft-deleted device's incident");
        assertEquals(7001L, open.get(0).getId(),
                "only the active device's incident may survive the filter");
    }

    @Test
    void countOpenByPriority_excludesSoftDeletedDeviceIncident() {
        long total = 0;
        boolean criticalPresent = false;
        for (Object[] row : incidentRepository.countOpenByPriority()) {
            total += ((Number) row[1]).longValue();
            if ("CRITICAL".equals(String.valueOf(row[0]))) {
                criticalPresent = true;
            }
        }
        assertEquals(1, total, "only the active device's open incident may be counted");
        assertTrue(!criticalPresent,
                "the deleted device's CRITICAL incident must not appear in the priority buckets");
    }

    @Test
    void countOpenedInRange_excludesSoftDeletedDeviceIncident() {
        Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant to = Instant.now().plus(1, ChronoUnit.DAYS);

        assertEquals(1, incidentRepository.countOpenedInRange(null, from, to),
                "report incident count must exclude soft-deleted devices");
    }
}
