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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V40 schema + persistence guardrails for the server-anchored playback schedule (Variant A). Also
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

        var found = scheduleRepository.findByAssignmentIdAndVersionNumber(500L, 1).orElseThrow();
        assertEquals(1_719_830_400_000L, found.getAnchorEpochMs(), "anchor persists as epoch ms (BIGINT)");
        assertEquals(found.getActivateAtEpochMs(), found.getAnchorEpochMs(), "anchor == activate (loop T0)");
        assertEquals("v-hash", found.getContentVersion());
        assertEquals(1, found.getVersionNumber());
    }

    @Test
    void uniqueConstraint_blocksSecondAnchorForSameAssignmentVersion() {
        new TransactionTemplate(txManager).executeWithoutResult(s ->
                scheduleRepository.save(new PlaybackSyncSchedule(600L, 2, "v1", Instant.ofEpochMilli(1_000L))));

        assertThrows(DataIntegrityViolationException.class, () ->
                        new TransactionTemplate(txManager).executeWithoutResult(s ->
                                scheduleRepository.save(
                                        new PlaybackSyncSchedule(600L, 2, "v2", Instant.ofEpochMilli(2_000L)))),
                "UNIQUE (assignment_id, version_number) must block a second anchor for one content-version");
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
