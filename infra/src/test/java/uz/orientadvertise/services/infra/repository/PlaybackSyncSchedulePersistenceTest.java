package uz.orientadvertise.services.infra.repository;

import javax.sql.DataSource;
import java.time.Instant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.orientadvertise.services.domain.model.PlaybackSyncSchedule;
import uz.orientadvertise.services.domain.repository.PlaybackSyncScheduleRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V40 + V53 schema and persistence guardrails for the server-anchored playback schedule (Variant A). Also
 * proves the {@link PlaybackSyncSchedule} entity maps 1:1 to the migration — Hibernate
 * {@code ddl-auto=validate} would fail context startup on any column/type drift.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class PlaybackSyncSchedulePersistenceTest {

    @Autowired private DataSource dataSource;
    @Autowired private PlaybackSyncScheduleRepository scheduleRepository;
    @Autowired private PlatformTransactionManager txManager;

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

    @Test
    void roundTrips_andAnchorEqualsActivateEpochMs() {
        Instant activateAt = Instant.ofEpochMilli(1_719_830_400_000L);
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                scheduleRepository.save(new PlaybackSyncSchedule(500L, 1, "v-hash", activateAt)));

        var found = scheduleRepository.findByAssignmentIdAndContentVersion(500L, "v-hash").orElseThrow();
        assertEquals(1_719_830_400_000L, found.getAnchorEpochMs(), "anchor persists as epoch ms (BIGINT)");
        assertEquals(found.getActivateAtEpochMs(), found.getAnchorEpochMs(), "anchor == activate (loop T0)");
        assertEquals("v-hash", found.getContentVersion());
        assertEquals(1, found.getVersionNumber());
    }

    @Test
    void uniqueConstraint_blocksASecondAnchorForTheSameContent() {
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                scheduleRepository.save(new PlaybackSyncSchedule(600L, 2, "v1", Instant.ofEpochMilli(1_000L))));

        assertThrows(DataIntegrityViolationException.class, () ->
                        new TransactionTemplate(txManager).executeWithoutResult(s ->
                                scheduleRepository.save(
                                        new PlaybackSyncSchedule(600L, 2, "v1", Instant.ofEpochMilli(2_000L)))),
                "uq_playback_sched_cv must block a second anchor for one (assignment, content version)");
    }

    @Test
    void twoContentVersionsOfOneAssignment_bothAnchor() {
        // VG-06 / V53: the OLD key was (assignment_id, version_number), and version_number never
        // moves — so this second row was rejected and an edited playlist kept the first anchor,
        // whose cut-over instant had long passed.
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            scheduleRepository.save(new PlaybackSyncSchedule(700L, 1, "v-before", Instant.ofEpochMilli(1_000L)));
            scheduleRepository.save(new PlaybackSyncSchedule(700L, 1, "v-after", Instant.ofEpochMilli(9_000L)));
        });

        assertEquals(1_000L, scheduleRepository.findByAssignmentIdAndContentVersion(700L, "v-before")
                .orElseThrow().getAnchorEpochMs());
        assertEquals(9_000L, scheduleRepository.findByAssignmentIdAndContentVersion(700L, "v-after")
                .orElseThrow().getAnchorEpochMs(), "the edit gets its own, later cut-over");
    }

    @Test
    void insertIfAbsent_writesOnce_thenAffectsNoRows() {
        // The statement that replaced the REQUIRES_NEW writer (VG-07): a lost race must be a 0, not
        // an exception, or it would poison the /sync transaction it now shares.
        Instant activateAt = Instant.ofEpochMilli(4_000L);
        Integer first = new TransactionTemplate(txManager).execute(s -> scheduleRepository.insertIfAbsent(
                800L, 1, "v-x", activateAt.toEpochMilli(), activateAt, Instant.now()));
        Integer second = new TransactionTemplate(txManager).execute(s -> scheduleRepository.insertIfAbsent(
                800L, 1, "v-x", 9_999L, Instant.ofEpochMilli(9_999L), Instant.now()));

        assertEquals(1, first);
        assertEquals(0, second, "ON CONFLICT DO NOTHING: the loser writes nothing and raises nothing");
        assertEquals(4_000L, scheduleRepository.findByAssignmentIdAndContentVersion(800L, "v-x")
                .orElseThrow().getAnchorEpochMs(), "the first anchor stands — it must never move");
    }

    @Test
    void existsByAssignmentId_reportsWhetherTheAssignmentIsAnchoredAtAll() {
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                scheduleRepository.save(new PlaybackSyncSchedule(900L, 1, "v", Instant.ofEpochMilli(1_000L))));

        assertTrue(scheduleRepository.existsByAssignmentId(900L));
        assertFalse(scheduleRepository.existsByAssignmentId(901L));
    }

    @Test
    void findByActivateAtAfter_returnsWindowedCutovers_excludesOld() {
        Instant now = Instant.now();
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            scheduleRepository.save(new PlaybackSyncSchedule(700L, 1, "vOld", now.minusSeconds(3600)));
            scheduleRepository.save(new PlaybackSyncSchedule(701L, 1, "vNew", now.plusSeconds(120)));
        });

        var recent = scheduleRepository.findByActivateAtAfter(now.minusSeconds(600));
        assertEquals(1, recent.size(), "only the within-window cut-over is returned");
        assertEquals(701L, recent.get(0).getAssignmentId());
    }
}
