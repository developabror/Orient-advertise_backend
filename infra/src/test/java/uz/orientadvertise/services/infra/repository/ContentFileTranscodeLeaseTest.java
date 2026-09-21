package uz.orientadvertise.services.infra.repository;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the v1.0.132 transcode-lease statements against a real database (H2 in PostgreSQL mode,
 * full Flyway schema) rather than a mock.
 *
 * <p>These are the statements the whole recovery design rests on, and a mock cannot prove any of the
 * three properties that matter: that the compare-and-set really is atomic, that {@code TRANSCODING}
 * is really <em>committed</em> (the previous pipeline set it on a managed entity and always
 * overwrote it before commit, which is what made the orphan recoverer's predicate unsatisfiable),
 * and that the candidate predicates really select the rows they claim to on both engines.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class ContentFileTranscodeLeaseTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ContentFileRepository repository;

    private final Instant now = Instant.parse("2026-09-06T12:00:00Z");

    @BeforeEach
    void setUp() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private ContentFile newUploadedFile() {
        return repository.saveAndFlush(
                new ContentFile(null, "clip.mp4", "video/mp4", 1024, "raw/" + System.nanoTime(), null));
    }

    /** Backdates a row's clocks; the entity stamps {@code createdAt} to "now" on construction. */
    private void backdate(Long id, String column, Instant value) {
        exec("UPDATE content_file SET " + column + " = ? WHERE id = ?",
                java.sql.Timestamp.from(value), id);
    }

    private void exec(String sql, Object... args) {
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    // ---------- the claim ----------

    @Test
    void claim_isCompareAndSet_secondAttemptLoses() {
        var file = newUploadedFile();

        assertEquals(1, repository.claimForTranscode(file.getId(), ContentFile.Status.UPLOADED, now));
        assertEquals(0, repository.claimForTranscode(file.getId(), ContentFile.Status.UPLOADED, now),
                "a second claim on an already-claimed row must lose");

        var reloaded = repository.findById(file.getId()).orElseThrow();
        assertEquals(ContentFile.Status.TRANSCODING, reloaded.getStatus());
        assertEquals(now, reloaded.getTranscodeStartedAt());
        assertEquals(1, reloaded.getTranscodeAttempts());
    }

    @Test
    void claim_isCommittedAndVisibleToAnIndependentConnection() {
        // TRANSCODING must be observable OUTSIDE the worker's own unit of work — otherwise the
        // sweeper cannot see a crashed encode, and GET /api/content/{id} disagrees with the live
        // feed for the whole run. Reading through a separate JDBC connection is the proof.
        var file = newUploadedFile();
        repository.claimForTranscode(file.getId(), ContentFile.Status.UPLOADED, now);

        String status = queryStatus(file.getId());
        assertEquals("TRANSCODING", status);
    }

    @Test
    void claim_underConcurrency_hasExactlyOneWinner() throws Exception {
        var file = newUploadedFile();
        var barrier = new CyclicBarrier(2);
        Callable<Integer> attempt = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return repository.claimForTranscode(file.getId(), ContentFile.Status.UPLOADED, now);
            } catch (RuntimeException e) {
                // A serialization/lock error is also "did not win" — what must never happen is TWO
                // winners, because that is two concurrent ffmpeg processes on one file.
                return 0;
            }
        };

        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(attempt);
            Future<Integer> b = pool.submit(attempt);
            int winners = a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS);
            assertEquals(1, winners, "exactly one caller may win the claim");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, repository.findById(file.getId()).orElseThrow().getTranscodeAttempts(),
                "the losing claim must not have incremented the attempt counter");
    }

    @Test
    void claim_skipsSoftDeletedRows() {
        var file = newUploadedFile();
        exec("UPDATE content_file SET deleted_at = ? WHERE id = ?", java.sql.Timestamp.from(now), file.getId());

        assertEquals(0, repository.claimForTranscode(file.getId(), ContentFile.Status.UPLOADED, now));
    }

    // ---------- the start guard ----------

    @Test
    void beginTranscode_refreshesTheLeaseOnlyWhileClaimed() {
        var file = newUploadedFile();
        assertEquals(0, repository.beginTranscode(file.getId(), now),
                "an unclaimed row must not start a pipeline");

        repository.claimForTranscode(file.getId(), ContentFile.Status.UPLOADED, now);
        Instant later = now.plus(3, ChronoUnit.MINUTES);
        assertEquals(1, repository.beginTranscode(file.getId(), later));

        assertEquals(later, repository.findById(file.getId()).orElseThrow().getTranscodeStartedAt());
        assertEquals(1, repository.findById(file.getId()).orElseThrow().getTranscodeAttempts(),
                "starting the pipeline must not double-count the attempt");
    }

    // ---------- terminal writes ----------

    @Test
    void markReady_writesEveryDeliveryField_andOnlyFromTranscoding() {
        var file = newUploadedFile();
        assertEquals(0, repository.markTranscodeReady(file.getId(), "processed/x.mp4", 999L,
                "abc", "thumbnails/y.jpg", 42, now), "READY is only reachable from TRANSCODING");

        repository.claimForTranscode(file.getId(), ContentFile.Status.UPLOADED, now);
        assertEquals(1, repository.markTranscodeReady(file.getId(), "processed/x.mp4", 999L,
                "abc", "thumbnails/y.jpg", 42, now));

        var ready = repository.findById(file.getId()).orElseThrow();
        assertEquals(ContentFile.Status.READY, ready.getStatus());
        assertEquals("processed/x.mp4", ready.getProcessedStorageKey());
        // The device downloads the PROCESSED object; a stale size makes it reject every file.
        assertEquals(999L, ready.getSizeBytes());
        assertEquals("abc", ready.getChecksum());
        assertEquals("thumbnails/y.jpg", ready.getThumbnailStorageKey());
        assertEquals(42, ready.getDurationSeconds());
        assertNull(ready.getTranscodeLastError());
    }

    @Test
    void markFailed_recordsTheReason_andMarkInvalidClearsIt() {
        var failed = newUploadedFile();
        repository.claimForTranscode(failed.getId(), ContentFile.Status.UPLOADED, now);
        assertEquals(1, repository.markTranscodeFailed(failed.getId(), "ffmpeg exit=1", now));
        var reloadedFailed = repository.findById(failed.getId()).orElseThrow();
        assertEquals(ContentFile.Status.FAILED, reloadedFailed.getStatus());
        assertEquals("ffmpeg exit=1", reloadedFailed.getTranscodeLastError());

        var invalid = newUploadedFile();
        repository.claimForTranscode(invalid.getId(), ContentFile.Status.UPLOADED, now);
        assertEquals(1, repository.markTranscodeInvalid(invalid.getId(), "unreadable container", now));
        var reloadedInvalid = repository.findById(invalid.getId()).orElseThrow();
        assertEquals(ContentFile.Status.INVALID, reloadedInvalid.getStatus());
        assertEquals("unreadable container", reloadedInvalid.getInvalidReason());
        assertNull(reloadedInvalid.getTranscodeLastError());
    }

    @Test
    void abandonAndReset_bracketTheAttemptBudget() {
        var file = newUploadedFile();
        assertEquals(1, repository.abandonTranscode(file.getId(), ContentFile.Status.UPLOADED,
                "Abandoned after 3 transcode attempt(s)", now));
        var abandoned = repository.findById(file.getId()).orElseThrow();
        assertEquals(ContentFile.Status.FAILED, abandoned.getStatus());
        assertTrue(abandoned.getTranscodeLastError().contains("Abandoned"));

        // An operator retry clears the budget so earlier automatic attempts don't cap a human.
        exec("UPDATE content_file SET transcode_attempts = 3 WHERE id = ?", file.getId());
        assertEquals(1, repository.resetTranscodeAttempts(file.getId(), now));
        assertEquals(0, repository.findById(file.getId()).orElseThrow().getTranscodeAttempts());
    }

    // ---------- candidate selection ----------

    @Test
    void lostDispatchCandidates_selectOldUploadedRowsOnly() {
        var fresh = newUploadedFile();
        var old = newUploadedFile();
        var deleted = newUploadedFile();
        backdate(old.getId(), "created_at", now.minus(30, ChronoUnit.MINUTES));
        backdate(deleted.getId(), "created_at", now.minus(30, ChronoUnit.MINUTES));
        exec("UPDATE content_file SET deleted_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now), deleted.getId());

        var candidates = repository.findLostDispatchCandidates(
                now.minus(5, ChronoUnit.MINUTES), PageRequest.of(0, 50));

        var ids = candidates.stream().map(ContentFile::getId).toList();
        assertTrue(ids.contains(old.getId()), "an aged UPLOADED row is a lost dispatch");
        assertTrue(!ids.contains(fresh.getId()), "a fresh upload is still in flight, not lost");
        assertTrue(!ids.contains(deleted.getId()), "soft-deleted rows are never re-driven");
    }

    @Test
    void expiredLeaseCandidates_ignoreAHealthyInFlightEncode() {
        // The anti-double-dispatch guarantee, proven at the SQL level: a row leased one minute ago
        // must be invisible to a 20-minute cutoff, no matter how long ffmpeg has been running.
        var running = newUploadedFile();
        repository.claimForTranscode(running.getId(), ContentFile.Status.UPLOADED, now);
        backdate(running.getId(), "transcode_started_at", now.minus(1, ChronoUnit.MINUTES));

        var crashed = newUploadedFile();
        repository.claimForTranscode(crashed.getId(), ContentFile.Status.UPLOADED, now);
        backdate(crashed.getId(), "transcode_started_at", now.minus(45, ChronoUnit.MINUTES));

        var candidates = repository.findExpiredLeaseCandidates(
                now.minus(20, ChronoUnit.MINUTES), PageRequest.of(0, 50));

        var ids = candidates.stream().map(ContentFile::getId).toList();
        assertTrue(ids.contains(crashed.getId()), "an expired lease means the encode died");
        assertTrue(!ids.contains(running.getId()), "a fresh lease must never be reclaimed");
    }

    @Test
    void retryableFailedCandidates_respectTheAttemptCap() {
        var retryable = newUploadedFile();
        exec("UPDATE content_file SET status = 'FAILED', transcode_attempts = 1, transcode_started_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.minus(60, ChronoUnit.MINUTES)), retryable.getId());

        var exhausted = newUploadedFile();
        exec("UPDATE content_file SET status = 'FAILED', transcode_attempts = 3, transcode_started_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.minus(60, ChronoUnit.MINUTES)), exhausted.getId());

        var recentlyTried = newUploadedFile();
        exec("UPDATE content_file SET status = 'FAILED', transcode_attempts = 1, transcode_started_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.minus(1, ChronoUnit.MINUTES)), recentlyTried.getId());

        var candidates = repository.findRetryableFailedCandidates(
                3, now.minus(15, ChronoUnit.MINUTES), PageRequest.of(0, 50));

        var ids = candidates.stream().map(ContentFile::getId).toList();
        assertTrue(ids.contains(retryable.getId()));
        assertTrue(!ids.contains(exhausted.getId()), "a poison file must stop being retried");
        assertTrue(!ids.contains(recentlyTried.getId()), "the cooldown spaces retries out");
    }

    @Test
    void countStaleUploaded_isTheBacklogGauge() {
        var old = newUploadedFile();
        backdate(old.getId(), "created_at", now.minus(30, ChronoUnit.MINUTES));
        newUploadedFile(); // fresh — not yet a problem

        assertEquals(1, repository.countStaleUploaded(now.minus(10, ChronoUnit.MINUTES)));

        repository.claimForTranscode(old.getId(), ContentFile.Status.UPLOADED, now);
        assertEquals(0, repository.countStaleUploaded(now.minus(10, ChronoUnit.MINUTES)),
                "once claimed, a file is no longer part of the backlog");
    }

    private String queryStatus(Long id) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT status FROM content_file WHERE id = ?")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String status = rs.getString(1);
                assertNotNull(status);
                return status;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
