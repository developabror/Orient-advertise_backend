package uz.orientadvertise.services.infra.repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real-DB integration test for {@link PlaybackLogRepository#aggregatePerContentForDevice} — the
 * one thing the mock-based service test cannot verify: the JPQL itself (COALESCE duration
 * fallback, the both-sources-null missing count, the device + window WHERE clause, and the
 * {@code playCount DESC, contentFileName ASC} ordering). Mirrors the
 * {@code @SpringBootTest + Flyway + autowired repository} idiom of the migration integration tests.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class PlaybackLogDeviceAggregationTest {

    private static final long DEVICE = 3001L;
    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-06-30T00:00:00Z");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlaybackLogRepository playbackLogRepository;

    @BeforeEach
    void setUp() throws Exception {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        try (Connection conn = dataSource.getConnection(); Statement s = conn.createStatement()) {
            s.execute("INSERT INTO project (id, name, created_at, updated_at) "
                    + "VALUES (300, 'AggProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                    + "VALUES (300, 300, 'AggRegion', 'AR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) "
                    + "VALUES (3001, 300, 'SN-AGG-1', 'Lobby TV-1', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) "
                    + "VALUES (3002, 300, 'SN-AGG-2', 'Other TV', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");

            // content 70 has a 30s catalog duration; 71 has NONE (drives missing); 72/73 have catalog too.
            content(s, 70, "Summer Promo 30s", 30);
            content(s, 71, "Store Hours", null);
            content(s, 72, "Zebra", 10);
            content(s, 73, "Apple", 10);

            // content 70 on DEVICE: 1 reported (25) + 2 null→catalog(30) ⇒ count 3, sum 85, missing 0
            play(s, 3001, 70, "2026-06-15 10:00:00", 25);
            play(s, 3001, 70, "2026-06-15 11:00:00", null);
            play(s, 3001, 70, "2026-06-15 12:00:00", null);
            // content 71 on DEVICE: 2 null, catalog null ⇒ count 2, sum 0, missing 2
            play(s, 3001, 71, "2026-06-16 10:00:00", null);
            play(s, 3001, 71, "2026-06-16 11:00:00", null);
            // content 72 "Zebra" + 73 "Apple": each 1 reported play (same count ⇒ name-ASC tiebreak)
            play(s, 3001, 72, "2026-06-17 10:00:00", 50);
            play(s, 3001, 73, "2026-06-17 11:00:00", 5);
            // EXCLUDED: out-of-window (before FROM) and a different device — must not be counted.
            play(s, 3001, 70, "2026-05-01 10:00:00", 99);
            play(s, 3002, 70, "2026-06-15 10:00:00", 99);
        }
    }

    private void content(Statement s, long id, String name, Integer duration) throws Exception {
        s.execute("INSERT INTO content_file (id, project_id, name, content_type, size_bytes, storage_key, duration_seconds) "
                + "VALUES (" + id + ", 300, '" + name + "', 'video/mp4', 1000, 'key-" + id + "', "
                + (duration == null ? "NULL" : duration) + ")");
    }

    private void play(Statement s, long device, long content, String playedAt, Integer duration) throws Exception {
        s.execute("INSERT INTO playback_log (device_id, content_file_id, played_at, duration_seconds) "
                + "VALUES (" + device + ", " + content + ", TIMESTAMP '" + playedAt + "', "
                + (duration == null ? "NULL" : duration) + ")");
    }

    @Test
    void aggregatePerContentForDevice_coalesceMissingAndOrdering() {
        List<Object[]> rows = playbackLogRepository.aggregatePerContentForDevice(DEVICE, FROM, TO);

        assertEquals(4, rows.size(), "one row per distinct in-window content on this device");

        // Ordered playCount DESC, then contentFileName ASC: 70(3), 71(2), then Apple(73) before Zebra(72).
        assertRow(rows.get(0), 70L, "Summer Promo 30s", 3L, 85L, 0L); // 25 + 30(catalog) + 30(catalog)
        assertRow(rows.get(1), 71L, "Store Hours", 2L, 0L, 2L);       // both sources null → missing 2
        assertRow(rows.get(2), 73L, "Apple", 1L, 5L, 0L);             // name-ASC tiebreak before Zebra
        assertRow(rows.get(3), 72L, "Zebra", 1L, 50L, 0L);
    }

    private void assertRow(Object[] r, long contentId, String name, long count, long durationSum, long missing) {
        assertEquals(contentId, ((Number) r[0]).longValue());
        assertEquals(name, r[1]);
        assertEquals(count, ((Number) r[2]).longValue());
        assertEquals(durationSum, ((Number) r[3]).longValue());
        assertEquals(missing, ((Number) r[4]).longValue());
    }
}
