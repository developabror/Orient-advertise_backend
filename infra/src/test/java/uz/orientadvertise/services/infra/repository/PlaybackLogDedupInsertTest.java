package uz.orientadvertise.services.infra.repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.orientadvertise.services.domain.model.PlaybackLog;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-schema guard for {@link PlaybackLogRepository#insertIgnoringDuplicate} — the statement that
 * replaced catch-based dedup after a single duplicate entry was found to discard whole batches of
 * production playback telemetry.
 *
 * <p>The mock-based service tests cannot verify any of this: that the targetless
 * {@code ON CONFLICT DO NOTHING} parses at all under H2 {@code MODE=PostgreSQL}, that it returns
 * exactly 0/1, that a conflict leaves the surrounding transaction <em>usable</em> rather than
 * poisoned, and — the silent-corruption risk — that the native binding writes the same
 * {@code Instant} the JPA path does.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class PlaybackLogDedupInsertTest {

    private static final long DEVICE = 5001L;
    private static final long OTHER_DEVICE = 5002L;
    private static final long CONTENT = 90L;
    private static final long OTHER_CONTENT = 91L;
    private static final long MISSING_DEVICE = 999_999L;

    private static TimeZone originalTimeZone;

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaybackLogRepository repository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private ContentFileRepository contentFileRepository;
    @Autowired private PlatformTransactionManager txManager;

    private Instant t1;
    private Instant t2;
    private Instant t3;

    /**
     * A UTC-only JVM would hide an offset bug in the native bind path: if
     * {@code insertIgnoringDuplicate} bound {@code Instant} differently from Hibernate's entity
     * write, every row would shift by the JVM offset and dedup would silently stop matching the
     * millions of historical rows. Asia/Tashkent (UTC+5, no DST) makes such a shift visible.
     */
    @BeforeAll
    static void useNonUtcTimeZone() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tashkent"));
    }

    @AfterAll
    static void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

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
                    + "VALUES (500, 'DedupProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                    + "VALUES (500, 500, 'DedupRegion', 'DR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) "
                    + "VALUES (" + DEVICE + ", 500, 'SN-DEDUP-1', 'Dedup TV', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) "
                    + "VALUES (" + OTHER_DEVICE + ", 500, 'SN-DEDUP-2', 'Other TV', 'ONLINE', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO content_file (id, project_id, name, content_type, size_bytes, storage_key) "
                    + "VALUES (" + CONTENT + ", 500, 'Dedup Clip', 'video/mp4', 1000, 'key-dedup')");
            s.execute("INSERT INTO content_file (id, project_id, name, content_type, size_bytes, storage_key) "
                    + "VALUES (" + OTHER_CONTENT + ", 500, 'Other Clip', 'video/mp4', 1000, 'key-other')");
        }

        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(600);
        t1 = base;
        t2 = base.plusSeconds(30);
        t3 = base.plusSeconds(60);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * {@code @Modifying} native queries require an active transaction, so every call goes through
     * a {@link TransactionTemplate} — which is also what makes the commit-vs-rollback assertions
     * below meaningful.
     */
    private int insert(long deviceId, long contentFileId, Instant playedAt) {
        Integer n = new TransactionTemplate(txManager).execute(s ->
                repository.insertIgnoringDuplicate(deviceId, contentFileId, null, playedAt, 30, Instant.now()));
        return n == null ? -1 : n;
    }

    private int rowCount() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM playback_log", Integer.class);
        return n == null ? -1 : n;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void insert_firstTimeReturnsOne_sameKeyReturnsZero() {
        assertEquals(1, insert(DEVICE, CONTENT, t1));
        assertEquals(0, insert(DEVICE, CONTENT, t1));
        assertEquals(1, rowCount());
    }

    /**
     * THE core assertion. A duplicate in the middle of a transaction must neither raise nor
     * poison it: the transaction commits and every non-duplicate row survives. Against the
     * pre-fix {@code repository.save()} path this transaction dies with
     * {@code UnexpectedRollbackException} and leaves 1 row.
     */
    @Test
    void duplicateInsideATransaction_doesNotPoisonIt_transactionCommitsWithTheOtherRows() {
        assertEquals(1, insert(DEVICE, CONTENT, t1));

        Integer affected = new TransactionTemplate(txManager).execute(s -> {
            int dup = repository.insertIgnoringDuplicate(DEVICE, CONTENT, null, t1, 30, Instant.now());
            int a = repository.insertIgnoringDuplicate(DEVICE, CONTENT, null, t2, 30, Instant.now());
            int b = repository.insertIgnoringDuplicate(DEVICE, CONTENT, null, t3, 30, Instant.now());
            assertEquals(0, dup, "the duplicate must be skipped, not raised");
            return dup + a + b;
        });

        assertEquals(2, affected);
        assertEquals(3, rowCount(), "the transaction must have COMMITTED with all three rows present");
    }

    @Test
    void sameKeyTwiceInsideOneTransaction_secondReturnsZero() {
        Integer affected = new TransactionTemplate(txManager).execute(s -> {
            int first = repository.insertIgnoringDuplicate(DEVICE, CONTENT, null, t1, 30, Instant.now());
            int second = repository.insertIgnoringDuplicate(DEVICE, CONTENT, null, t1, 30, Instant.now());
            assertEquals(1, first);
            assertEquals(0, second, "the unique index sees the transaction's own uncommitted tuple");
            return first + second;
        });

        assertEquals(1, affected);
        assertEquals(1, rowCount());
    }

    /**
     * Binding equivalence between the native insert and the JPA entity write — the one silent
     * corruption risk in swapping the write path. Both rows must be found by the same
     * {@code exists} probe and read back with the exact {@code Instant}s written.
     */
    @Test
    void nativeInsertAndEntitySave_bindTheSameInstant() {
        // JPA path
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            var device = deviceRepository.findById(DEVICE).orElseThrow();
            var content = contentFileRepository.findById(CONTENT).orElseThrow();
            repository.save(new PlaybackLog(device, content, null, t1, 30));
        });
        // native path
        assertEquals(1, insert(DEVICE, CONTENT, t2));

        assertTrue(repository.existsByDeviceIdAndContentFileIdAndPlayedAt(DEVICE, CONTENT, t1),
                "entity-written row not found at the exact Instant it was written");
        assertTrue(repository.existsByDeviceIdAndContentFileIdAndPlayedAt(DEVICE, CONTENT, t2),
                "natively-written row not found at the exact Instant it was written — the native "
                        + "bind path shifted the timestamp relative to the JPA path");

        var readBack = repository.findByContentFileIdOrderByPlayedAtDesc(CONTENT);
        assertEquals(2, readBack.size());
        assertEquals(t2, readBack.get(0).getPlayedAt());
        assertEquals(t1, readBack.get(1).getPlayedAt());

        // Cross-check: the native row must also be a duplicate to the JPA path's own key space.
        assertEquals(0, insert(DEVICE, CONTENT, t1), "the entity-written row must dedup natively");
    }

    /**
     * Both nullable columns bound as null in one statement. The CASTs exist precisely so a null
     * bind cannot reach the database as an untyped NULL needing inference from context; a null
     * {@code duration_seconds} is routine in production (the device omits it) and is otherwise
     * only covered for {@code assignment_id}.
     */
    @Test
    void nullAssignmentAndNullDuration_bindThroughTheCasts() {
        Integer affected = new TransactionTemplate(txManager).execute(s ->
                repository.insertIgnoringDuplicate(DEVICE, CONTENT, null, t1, null, Instant.now()));

        assertEquals(1, affected);
        assertEquals(1, rowCount());
        assertTrue(repository.existsByDeviceIdAndContentFileIdAndPlayedAt(DEVICE, CONTENT, t1));

        var row = repository.findByContentFileIdOrderByPlayedAtDesc(CONTENT).get(0);
        assertNull(row.getDurationSeconds(), "null duration must persist as NULL, not 0");
        assertNull(row.getAssignment(), "null assignment must persist as NULL");

        // And the null-duration row still dedups on the same key.
        Integer again = new TransactionTemplate(txManager).execute(s ->
                repository.insertIgnoringDuplicate(DEVICE, CONTENT, null, t1, null, Instant.now()));
        assertEquals(0, again);
    }

    @Test
    void differentDeviceContentOrTimestamp_eachInsertsANewRow() {
        assertEquals(1, insert(DEVICE, CONTENT, t1));

        assertEquals(1, insert(OTHER_DEVICE, CONTENT, t1), "different device_id is a distinct key");
        assertEquals(1, insert(DEVICE, OTHER_CONTENT, t1), "different content_file_id is a distinct key");
        assertEquals(1, insert(DEVICE, CONTENT, t2), "different played_at is a distinct key");

        assertEquals(4, rowCount());
    }

    /**
     * {@code ON CONFLICT DO NOTHING} covers unique/exclusion conflicts only. A foreign-key
     * violation is a real defect and must still propagate rather than be silently swallowed.
     */
    @Test
    void unknownDeviceId_stillThrowsForeignKeyViolation() {
        assertThrows(DataIntegrityViolationException.class,
                () -> insert(MISSING_DEVICE, CONTENT, t1));

        assertEquals(0, rowCount());
    }
}
