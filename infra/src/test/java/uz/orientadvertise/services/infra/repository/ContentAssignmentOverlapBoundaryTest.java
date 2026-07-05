package uz.orientadvertise.services.infra.repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentAssignment.TargetType;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the HALF-OPEN semantics of {@link ContentAssignmentRepository#findOverlapping}: the
 * window is {@code [start, end)}, so a new window whose start equals an existing window's end
 * (a back-to-back booking) must NOT be reported as a conflict. Runs the real JPQL against H2
 * (PostgreSQL mode) with the full Flyway schema, mirroring {@code DeviceStatusViewUnassignedFilterTest}.
 *
 * <p>Guards the actionable-409 work: the structured conflict response is only useful if the
 * underlying overlap detection keeps its exclusive {@code startTime < :end AND endTime > :start}
 * bounds — a regression to {@code <=}/{@code >=} would reject legal adjacent bookings.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class ContentAssignmentOverlapBoundaryTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ContentAssignmentRepository assignmentRepository;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private ProjectRepository projectRepository;

    private final Instant windowStart = Instant.parse("2026-06-02T06:00:00Z");
    private final Instant windowEnd = Instant.parse("2026-06-02T18:00:00Z");

    @BeforeEach
    void setUp() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        // One CONFIRMED assignment occupies REGION:1 over [06:00, 18:00).
        var project = projectRepository.save(new Project("BoundaryProj", null));
        var playlist = playlistRepository.save(new Playlist(project, "BoundaryPL", null));
        assignmentRepository.save(new ContentAssignment(
                playlist, TargetType.REGION, 1L, windowStart, windowEnd));
    }

    @Test
    void findOverlapping_newStartEqualsExistingEnd_isNotAConflict() {
        // New window [18:00, 19:00): start == existing end ⇒ half-open ⇒ NO overlap.
        var result = assignmentRepository.findOverlapping(
                TargetType.REGION, 1L, windowEnd, windowEnd.plus(1, ChronoUnit.HOURS));
        assertTrue(result.isEmpty(),
                "start == existing.end must be allowed (bounds exclusive: startTime < end AND endTime > start)");
    }

    @Test
    void findOverlapping_newEndEqualsExistingStart_isNotAConflict() {
        // New window [05:00, 06:00): end == existing start ⇒ half-open ⇒ NO overlap.
        var result = assignmentRepository.findOverlapping(
                TargetType.REGION, 1L, windowStart.minus(1, ChronoUnit.HOURS), windowStart);
        assertTrue(result.isEmpty(), "end == existing.start must be allowed (back-to-back booking)");
    }

    @Test
    void findOverlapping_genuineOverlap_isReported() {
        // New window [12:00, 20:00): straddles the existing end ⇒ real overlap.
        var result = assignmentRepository.findOverlapping(
                TargetType.REGION, 1L,
                Instant.parse("2026-06-02T12:00:00Z"), Instant.parse("2026-06-02T20:00:00Z"));
        assertEquals(1, result.size(), "a window that truly intersects must be reported");
    }
}
