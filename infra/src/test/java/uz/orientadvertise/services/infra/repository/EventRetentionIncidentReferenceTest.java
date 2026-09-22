package uz.orientadvertise.services.infra.repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real-schema guard for DATA-03: event retention must never pick an event that an incident
 * references, because {@code incident.first_event_id}/{@code last_event_id} are plain foreign keys
 * and one such row fails the whole delete batch. The service test mocks the repository, so only a
 * real schema shows whether the ids it returns can actually be deleted.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class EventRetentionIncidentReferenceTest {

    private static final long DEVICE = 7001L;
    private static final long FREE_EVENT = 7101L;
    private static final long RESOLVED_FIRST = 7102L;
    private static final long RESOLVED_LAST = 7103L;
    private static final long OPEN_FIRST = 7104L;
    private static final long RECENT_EVENT = 7105L;

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EventRepository eventRepository;
    @Autowired private PlatformTransactionManager txManager;

    private final Instant threshold = Instant.now().minus(90, ChronoUnit.DAYS);

    @BeforeEach
    void setUp() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        String old = "TIMESTAMP '2020-01-01 00:00:00'";
        try (Connection conn = dataSource.getConnection(); Statement s = conn.createStatement()) {
            s.execute("INSERT INTO project (id, name, created_at, updated_at) "
                    + "VALUES (700, 'RetentionProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                    + "VALUES (700, 700, 'RetentionRegion', 'RR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) "
                    + "VALUES (" + DEVICE + ", 700, 'SN-RET-1', 'Retention TV', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            for (long id : new long[] {FREE_EVENT, RESOLVED_FIRST, RESOLVED_LAST, OPEN_FIRST}) {
                s.execute("INSERT INTO event (id, device_id, event_type, priority, occurred_at) "
                        + "VALUES (" + id + ", " + DEVICE + ", 'DEVICE_OFFLINE', 'CRITICAL', " + old + ")");
            }
            s.execute("INSERT INTO event (id, device_id, event_type, priority, occurred_at) "
                    + "VALUES (" + RECENT_EVENT + ", " + DEVICE + ", 'DEVICE_OFFLINE', 'CRITICAL', CURRENT_TIMESTAMP)");
            // The case DATA-03 is about: a RESOLVED incident (auto-resolve makes these common).
            s.execute("INSERT INTO incident (device_id, event_type, status, priority, first_event_id, "
                    + "last_event_id, opened_at, resolved_at) VALUES (" + DEVICE + ", 'DEVICE_OFFLINE', "
                    + "'RESOLVED', 'CRITICAL', " + RESOLVED_FIRST + ", " + RESOLVED_LAST + ", " + old + ", " + old + ")");
            s.execute("INSERT INTO incident (device_id, event_type, status, priority, first_event_id, "
                    + "last_event_id, opened_at) VALUES (" + DEVICE + ", 'DEVICE_OFFLINE', "
                    + "'OPEN', 'CRITICAL', " + OPEN_FIRST + ", " + OPEN_FIRST + ", " + old + ")");
        }
    }

    private List<Long> expiredIds() {
        return eventRepository.findExpiredIdsNotReferencedByIncidents(threshold, PageRequest.of(0, 100));
    }

    private int eventCount() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM event", Integer.class);
        return n == null ? -1 : n;
    }

    @Test
    void skipsEventsOfResolvedAndOpenIncidents_returnsOnlyUnreferencedExpiredEvents() {
        assertEquals(List.of(FREE_EVENT), expiredIds());
    }

    @Test
    void returnedIdsCanActuallyBeDeleted_andTheNextRunFindsNothing() {
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                eventRepository.deleteAllByIdInBatch(expiredIds()));

        assertEquals(4, eventCount());          // 5 seeded − the one free expired event
        assertEquals(List.of(), expiredIds());  // nothing left to fail on, nothing left to delete
    }

    @Test
    void deletingAResolvedIncidentsEvent_isWhatBrokeTheOldQuery() {
        // The pre-fix query returned this id; its delete is what failed every nightly batch.
        assertThrows(DataIntegrityViolationException.class, () ->
                new TransactionTemplate(txManager).executeWithoutResult(s ->
                        eventRepository.deleteAllByIdInBatch(List.of(RESOLVED_FIRST))));
    }
}
