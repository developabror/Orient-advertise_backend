package uz.orientadvertise.services.infra.repository;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentAssignment.TargetType;
import uz.orientadvertise.services.domain.model.ContentAssignmentExclusion;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.ContentAssignmentExclusionRepository;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the END STATE a partial-device supersede leaves behind (v1.0.135) against the REAL JPQL
 * on H2 (PostgreSQL mode) under the full Flyway schema — the mocked
 * {@code ContentAssignmentServiceTest} can pin the decision but not the queries that have to agree
 * with it. Sibling of {@link ContentAssignmentOverlapBoundaryTest}.
 *
 * <p>Scenario: region {@code R} = devices [handover, remainder], both driven by predecessor P
 * (playlist P1). The operator reassigns playlist P2 to the handover device only and hits Replace,
 * so P is narrowed by one {@link ContentAssignmentExclusion} instead of being retired.
 *
 * <p>Module boundary: {@code infra} cannot depend on {@code service}, so the confirm is reproduced
 * here as the rows it commits, and the resolution is reproduced as the exact query pair
 * {@code ContentAssignmentService.resolveForDevice} composes — {@code findActiveAtTime} plus
 * {@code ContentAssignmentExclusionRepository.findByDeviceId}, max by priority. That is the point:
 * the rows a narrowed predecessor leaves behind must resolve correctly through the production
 * queries.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class ContentAssignmentPartialSupersedeTest {

    @Autowired private DataSource dataSource;
    @Autowired private ContentAssignmentRepository assignmentRepository;
    @Autowired private ContentAssignmentExclusionRepository exclusionRepository;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RegionRepository regionRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private PlatformTransactionManager txManager;

    private final Instant windowStart = Instant.parse("2026-06-02T06:00:00Z");
    private final Instant forever = Instant.parse("2100-01-01T00:00:00Z");
    private final Instant atTime = Instant.parse("2026-06-02T12:00:00Z");

    private Device handover;
    private Device remainder;
    private Long oldPlaylistId;
    private Long newPlaylistId;
    private ContentAssignment predecessor;
    private ContentAssignment replacement;

    @BeforeEach
    void setUp() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        Project project = projectRepository.save(new Project("PartialSupersedeProj", null));
        Region region = regionRepository.save(new Region(project, "Toshkent", "tsh"));
        Playlist oldPlaylist = playlistRepository.save(new Playlist(project, "Korzinka promo", null));
        Playlist newPlaylist = playlistRepository.save(new Playlist(project, "Yangi reklama", null));
        oldPlaylistId = oldPlaylist.getId();
        newPlaylistId = newPlaylist.getId();

        handover = deviceRepository.save(new Device(region, null, "SN-HANDOVER", "Handover"));
        remainder = deviceRepository.save(new Device(region, null, "SN-REMAINDER", "Remainder"));

        // P drives the whole region with the old playlist.
        predecessor = assignmentRepository.save(new ContentAssignment(
                oldPlaylist, TargetType.REGION, region.getId(), windowStart, forever));

        // The confirm's end state: P narrowed by ONE exclusion (the handed-over device), still
        // CONFIRMED with its original window; the new assignment covers the region and excludes
        // the remainder device.
        exclusionRepository.save(new ContentAssignmentExclusion(predecessor, handover,
                "superseded for these devices by assignment 2"));
        replacement = assignmentRepository.save(new ContentAssignment(
                newPlaylist, TargetType.REGION, region.getId(), windowStart, forever));
        exclusionRepository.save(new ContentAssignmentExclusion(replacement, remainder, "not selected"));
    }

    @Test
    void narrowedPredecessor_isStillReturnedByFindActiveAtTime() {
        var active = assignmentRepository.findActiveAtTime(atTime);

        assertTrue(active.stream().anyMatch(a -> a.getId().equals(predecessor.getId())),
                "narrowing must NOT retire the predecessor — it keeps driving its remainder devices");
        assertEquals(2, active.size(), "both the narrowed predecessor and the replacement are active");
        var reloaded = assignmentRepository.findById(predecessor.getId()).orElseThrow();
        assertFalse(reloaded.isDeleted(), "not soft-deleted");
        assertEquals(ContentAssignment.Status.CONFIRMED, reloaded.getStatus());
        assertEquals(forever, reloaded.getEndTime(), "window untouched — no truncation");
    }

    @Test
    void handoverDevice_resolvesTheNewPlaylist_remainderKeepsTheOld() {
        assertEquals(newPlaylistId, resolvedPlaylistId(handover),
                "the reassigned device moves to the new playlist");
        assertEquals(oldPlaylistId, resolvedPlaylistId(remainder),
                "THE BUG: an unselected device must keep playing the old playlist, not go dark");
    }

    @Test
    void narrowedPredecessor_versionNumberUnchanged_soRemainderDoesNotReDownload() {
        // contentVersion is hashed from (assignmentId, versionNumber, playlistId, files) and never
        // reads exclusions — pinning versionNumber here pins "no re-download for the remainder".
        var reloaded = assignmentRepository.findById(predecessor.getId()).orElseThrow();
        assertEquals(1, reloaded.getVersionNumber(),
                "narrowing must not bump the version — remainder devices keep their content");
    }

    @Test
    void exclusionRows_areOnePerAssignmentPerDevice() {
        assertEquals(List.of(handover.getId()),
                exclusionRepository.findDeviceIdsByAssignmentId(predecessor.getId()),
                "the predecessor is narrowed by exactly the handed-over device");
        var byDevice = exclusionRepository.findByDeviceId(remainder.getId());
        assertEquals(1, byDevice.size(),
                "the remainder device is excluded from the NEW assignment only");
        assertNotNull(byDevice.get(0).getCreatedAt(), "createdAt records when the device left");
    }

    /**
     * Cancelling the superseding assignment must RELEASE the narrowing it caused (v1.0.142 review
     * follow-up). An exclusion is permanent and its FK points at the assignment it NARROWS, not at
     * the one that caused it — so the reason string
     * ({@code ContentAssignmentService.partialSupersedeReason}, which embeds the superseding id) is
     * the only join key, and {@code deleteByReason} is the query that uses it. Pinned here on the
     * real H2 schema because a JPQL bulk delete is exactly the kind of thing a mock cannot check.
     *
     * <p>The reason literal below is the one this fixture itself wrote; the service-side contract
     * that the WRITE and the DELETE format it identically is pinned in
     * {@code ContentAssignmentServiceTest}.
     */
    @Test
    void deleteByReason_releasesOnlyTheNarrowing_andTheHandoverDeviceResolvesThePredecessorAgain() {
        assertEquals(newPlaylistId, resolvedPlaylistId(handover), "precondition: handed over");

        // The two writes ContentAssignmentService.softDelete makes in ONE transaction when the
        // superseding assignment is cancelled: release its narrowings, soft-delete the row.
        int deleted = releaseNarrowing("superseded for these devices by assignment 2");
        replacement.softDelete();
        assignmentRepository.save(replacement);

        assertEquals(1, deleted, "exactly the narrowing row");
        assertEquals(oldPlaylistId, resolvedPlaylistId(handover),
                "the handover device resolves the predecessor again — not nothing. Without the "
                        + "release it would resolve NULL: excluded from the only assignment left");
        assertEquals(oldPlaylistId, resolvedPlaylistId(remainder), "the remainder device is unaffected");
        assertEquals(List.of(), exclusionRepository.findDeviceIdsByAssignmentId(predecessor.getId()),
                "the predecessor drives its whole target again");
        // The cancelled assignment's own exclusion (a different reason) must survive the release —
        // a cancel may not sweep every exclusion in sight.
        assertEquals(1, exclusionRepository.findByDeviceId(remainder.getId()).size(),
                "an unrelated exclusion is not swept up by the release");
    }

    /**
     * While the superseding assignment is still live, releasing the narrowing changes nothing about
     * who wins: PRECEDENCE decides the overlap, and the exclusion was only ever the v1.0.135
     * mechanism for keeping the remainder devices. Pins that the release is safe to run
     * unconditionally in {@code softDelete} (which also soft-deletes the row in the same breath).
     */
    @Test
    void deleteByReason_withTheSupersederStillLive_doesNotChangeTheWinner() {
        releaseNarrowing("superseded for these devices by assignment 2");

        assertEquals(newPlaylistId, resolvedPlaylistId(handover),
                "the more recently confirmed assignment still wins for the handed-over device");
        assertEquals(oldPlaylistId, resolvedPlaylistId(remainder),
                "and the remainder device keeps the predecessor via the NEW assignment's exclusion");
    }

    /** No narrowing under that key ⇒ nothing is deleted. A cancel must never sweep other rows. */
    @Test
    void deleteByReason_unknownKey_deletesNothing() {
        int deleted = releaseNarrowing("superseded for these devices by assignment 999");

        assertEquals(0, deleted);
        assertEquals(List.of(handover.getId()),
                exclusionRepository.findDeviceIdsByAssignmentId(predecessor.getId()),
                "every existing narrowing survives an unrelated cancel");
    }

    /**
     * The @Modifying delete needs an ambient transaction — supplied here by a
     * {@link TransactionTemplate}, as {@code @Transactional softDelete} supplies it in production
     * (same shape as {@code DeviceVolumeBulkUpdateRepositoryTest}).
     */
    private int releaseNarrowing(String reason) {
        return new TransactionTemplate(txManager)
                .execute(status -> exclusionRepository.deleteByReason(reason));
    }

    /** The exact resolution {@code ContentAssignmentService.resolveForDevice} performs. */
    private Long resolvedPlaylistId(Device device) {
        Set<Long> excluded = exclusionRepository.findByDeviceId(device.getId()).stream()
                .map(e -> e.getAssignment().getId())
                .collect(Collectors.toSet());
        return assignmentRepository.findActiveAtTime(atTime).stream()
                .filter(a -> !excluded.contains(a.getId()))
                .filter(a -> a.getTargetType() == TargetType.REGION
                        && a.getTargetId().equals(device.getRegion().getId()))
                .max(ContentAssignment.PRECEDENCE)
                .map(a -> a.getPlaylist().getId())
                .orElse(null);
    }
}
