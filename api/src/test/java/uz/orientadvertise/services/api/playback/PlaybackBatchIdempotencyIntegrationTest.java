package uz.orientadvertise.services.api.playback;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.Application;
import uz.orientadvertise.services.service.PlaybackLogService;
import uz.orientadvertise.services.service.PlaybackLogService.PlaybackEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end guard for the production outage in which a single duplicate playback entry
 * destroyed an entire batch.
 *
 * <p>The failure being locked out: {@code PlaybackLog} uses {@code GenerationType.IDENTITY}, so
 * {@code repository.save()} issues its INSERT eagerly inside the caller's transaction. A
 * {@code uq_playback_dedup} violation there aborts the transaction (PostgreSQL 23505 → 25P02 on
 * every later statement) and marks the Hibernate session rollback-only <em>before</em> any catch
 * block can run. The old catch-based dedup therefore returned a tidy {@code Duplicate} tally while
 * the commit threw {@link org.springframework.transaction.UnexpectedRollbackException} and
 * persisted nothing — the device saw a 500, retried forever per
 * {@code ANDROID_DEVICE_FLOW_SPEC.md}, and ~46 h of telemetry was discarded.
 *
 * <p>Dedup now happens <em>in</em> the INSERT ({@code ON CONFLICT DO NOTHING}), so a duplicate
 * never raises and the surrounding batch commits intact. Against the pre-fix code
 * {@link #batchWithLeadingDuplicate_commits_andKeepsEveryNonDuplicateEntry()} fails with
 * {@code UnexpectedRollbackException} and a row count of 1 — that behavioural diff is the point
 * of this class.
 */
@SpringBootTest(classes = Application.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlaybackBatchIdempotencyIntegrationTest {

    private static final long DEVICE = 4001L;
    private static final long CONTENT = 80L;
    private static final long UNKNOWN_CONTENT = 999_999L;

    @Autowired private PlaybackLogService playbackLogService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    private Instant t1;
    private Instant t2;
    private Instant t3;

    /**
     * Seeds directly through {@link JdbcTemplate} rather than re-running Flyway: {@code api}
     * depends on {@code infra} as {@code runtimeOnly} (strict layering), so the Flyway API is
     * deliberately off this compile classpath. The schema is already migrated — the test profile
     * enables Flyway at context start — so the fixture only has to own its own rows.
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM playback_log WHERE device_id = ?", DEVICE);

        seedOnce("project", 400,
                "INSERT INTO project (id, name, created_at, updated_at) "
                        + "VALUES (400, 'PlaybackIdemProj', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        seedOnce("region", 400,
                "INSERT INTO region (id, project_id, name, code, created_at, updated_at) "
                        + "VALUES (400, 400, 'IdemRegion', 'IR', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        seedOnce("device", DEVICE,
                "INSERT INTO device (id, region_id, serial_number, name, status, created_at, updated_at) "
                        + "VALUES (" + DEVICE + ", 400, 'SN-IDEM-1', 'Idem TV', 'ONLINE', "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        seedOnce("content_file", CONTENT,
                "INSERT INTO content_file (id, project_id, name, content_type, size_bytes, storage_key) "
                        + "VALUES (" + CONTENT + ", 400, 'Idem Clip', 'video/mp4', 1000, 'key-idem')");

        // Millisecond precision: H2/PostgreSQL TIMESTAMP does not round-trip nanos, and dedup
        // must compare the same value the DB stored.
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(600);
        t1 = base;
        t2 = base.plusSeconds(30);
        t3 = base.plusSeconds(60);
    }

    private void seedOnce(String table, long id, String insert) {
        Integer present = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, id);
        if (present == null || present == 0) {
            jdbcTemplate.update(insert);
        }
    }

    /** Scoped to this fixture's device so a sibling test class's rows cannot skew the count. */
    private int rowCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM playback_log WHERE device_id = ?", Integer.class, DEVICE);
        return n == null ? -1 : n;
    }

    @Test
    void firstBatch_singleEntry_isCreated() {
        var result = playbackLogService.recordBatch(DEVICE, List.of(entry(t1)));

        assertEquals(1, result.created());
        assertEquals(0, result.duplicate());
        assertEquals(0, result.rejected());
        assertEquals(1, rowCount());
    }

    /**
     * THE regression test. A batch whose first entry duplicates an already-stored row must still
     * commit the two fresh entries. Pre-fix this throws {@code UnexpectedRollbackException} at
     * commit and leaves the table at 1 row.
     */
    @Test
    void batchWithLeadingDuplicate_commits_andKeepsEveryNonDuplicateEntry() {
        playbackLogService.recordBatch(DEVICE, List.of(entry(t1)));
        assertEquals(1, rowCount());

        var result = playbackLogService.recordBatch(DEVICE,
                List.of(entry(t1), entry(t2), entry(t3)));

        assertEquals(2, result.created(), "the two fresh entries must survive the duplicate");
        assertEquals(1, result.duplicate());
        assertEquals(0, result.rejected());
        assertEquals(3, rowCount(), "a duplicate must not roll back the rest of the batch");
    }

    /** The "device retries forever" symptom becomes a clean, fully-duplicate no-op. */
    @Test
    void replayOfAnEntirelyDuplicateBatch_isANoOp() {
        playbackLogService.recordBatch(DEVICE, List.of(entry(t1), entry(t2), entry(t3)));
        assertEquals(3, rowCount());

        var replay = playbackLogService.recordBatch(DEVICE,
                List.of(entry(t1), entry(t2), entry(t3)));

        assertEquals(0, replay.created());
        assertEquals(3, replay.duplicate());
        assertEquals(0, replay.rejected());
        assertEquals(3, rowCount());
    }

    /**
     * Poison mix: a duplicate, a valid entry, and three separately-rejectable entries in one
     * batch. Every tally must be independent and exactly one new row must land.
     *
     * <p>The "not assigned to device" rejection is not exercisable here — this device has no
     * resolved assignment, so {@code recordBatch} skips the assignment check entirely. An
     * unknown {@code contentFileId} drives the same rejection path and is used instead.
     */
    @Test
    void poisonMixedBatch_talliesIndependently_andCommitsTheValidEntry() {
        playbackLogService.recordBatch(DEVICE, List.of(entry(t1)));
        assertEquals(1, rowCount());

        Instant ancient = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofDays(120));
        Instant future = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(600);

        var result = playbackLogService.recordBatch(DEVICE, List.of(
                entry(t1),                                        // duplicate
                entry(t2),                                        // valid
                entry(ancient),                                   // rejected: > 90 days
                entry(future),                                    // rejected: future
                new PlaybackEntry(UNKNOWN_CONTENT, t3, 30)));     // rejected: unknown content

        assertEquals(1, result.created());
        assertEquals(1, result.duplicate());
        assertEquals(3, result.rejected());
        assertEquals(3, result.rejections().size());
        assertEquals(2, rowCount(), "exactly one new row on top of the seeded one");
    }

    /**
     * The production symptom at the wire: a device POSTing a batch containing a known duplicate
     * must get HTTP 200 with a tally, not the 500 that made it retry forever.
     */
    @Test
    void postPlayback_withKnownDuplicate_returns200AndTallies() throws Exception {
        playbackLogService.recordBatch(DEVICE, List.of(entry(t1)));

        String body = """
                [{"contentFileId":%d,"playedAt":"%s","durationSeconds":30},
                 {"contentFileId":%d,"playedAt":"%s","durationSeconds":30}]
                """.formatted(CONTENT, t1, CONTENT, t2);

        mockMvc.perform(post("/api/devices/{id}/playback", DEVICE)
                        .with(authentication(deviceAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.duplicate").value(1))
                .andExpect(jsonPath("$.rejected").value(0));

        assertEquals(2, rowCount());
    }

    /**
     * Smoke test only: two overlapping flushes from the same device must both return normally and
     * leave exactly the union of distinct keys. H2's locking is not PostgreSQL's speculative
     * insertion, so this cannot prove the production race — it guards against an obvious
     * regression (an exception escaping under concurrency).
     */
    @Test
    void overlappingBatchesFromTheSameDevice_bothReturnNormally() throws Exception {
        var start = new CountDownLatch(1);
        var errorA = new AtomicReference<Throwable>();
        var errorB = new AtomicReference<Throwable>();

        Runnable a = () -> {
            try {
                start.await();
                playbackLogService.recordBatch(DEVICE, List.of(entry(t1), entry(t2)));
            } catch (Throwable e) {
                errorA.set(e);
            }
        };
        Runnable b = () -> {
            try {
                start.await();
                playbackLogService.recordBatch(DEVICE, List.of(entry(t2), entry(t3)));
            } catch (Throwable e) {
                errorB.set(e);
            }
        };

        var ta = new Thread(a);
        var tb = new Thread(b);
        ta.start();
        tb.start();
        start.countDown();
        ta.join(TimeUnit.SECONDS.toMillis(30));
        tb.join(TimeUnit.SECONDS.toMillis(30));

        assertNull(errorA.get(), () -> "thread A threw: " + errorA.get());
        assertNull(errorB.get(), () -> "thread B threw: " + errorB.get());
        assertTrue(rowCount() >= 3, "the union of distinct keys must be present, got " + rowCount());
        assertEquals(3, rowCount(), "t1, t2, t3 — t2 written exactly once");
    }

    private PlaybackEntry entry(Instant playedAt) {
        return new PlaybackEntry(CONTENT, playedAt, 30);
    }

    private UsernamePasswordAuthenticationToken deviceAuth() {
        // The device-token filter puts the numeric device id in the principal; the endpoint's
        // @PreAuthorize compares #id against exactly that.
        return new UsernamePasswordAuthenticationToken(
                DEVICE, null, List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
    }
}
