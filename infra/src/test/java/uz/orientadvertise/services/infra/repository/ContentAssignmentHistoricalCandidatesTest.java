package uz.orientadvertise.services.infra.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentAssignment.TargetType;
import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.domain.model.Facility;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.DeviceGroupRepository;
import uz.orientadvertise.services.domain.repository.FacilityRepository;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@code ContentAssignmentRepository.findHistoricalCandidates} — what a play reported after
 * the fact is checked against (VG-03) — on H2 in PostgreSQL mode under the full Flyway schema.
 *
 * <p>Its whole reason to exist is the two ways it must differ from {@code findActiveAtTime}:
 * it keeps rows soft-deleted <b>after</b> the window (a cancelled or replaced campaign still owns
 * the plays that happened while it ran) and it matches the window against a range rather than an
 * instant. A query that quietly reverted to {@code deletedAt IS NULL} would throw away exactly the
 * plays this fix recovers, and no unit test with a mocked repository would notice.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class ContentAssignmentHistoricalCandidatesTest {

    @Autowired private DataSource dataSource;
    @Autowired private ContentAssignmentRepository assignmentRepository;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RegionRepository regionRepository;
    @Autowired private FacilityRepository facilityRepository;
    @Autowired private DeviceGroupRepository deviceGroupRepository;

    /** The batch's span: plays reported for [from, to]. */
    private final Instant from = Instant.parse("2026-06-05T10:00:00Z");
    private final Instant to = Instant.parse("2026-06-05T11:00:00Z");

    private Region region;
    private Facility facility;
    private DeviceGroup deviceGroup;
    private Playlist playlist;

    @BeforeEach
    void setUp() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        var project = projectRepository.save(new Project("HistoryProj", null));
        region = regionRepository.save(new Region(project, "Toshkent", "tsh"));
        facility = facilityRepository.save(new Facility(region, "Chorsu", null));
        deviceGroup = deviceGroupRepository.save(new DeviceGroup(project, "Kassalar", null));
        playlist = playlistRepository.save(new Playlist(project, "Promo", null));
    }

    @Test
    void keepsACampaignSoftDeletedAfterTheWindow() {
        // Cancelled at 10:30 — it WAS driving the device for the first half of the batch.
        var cancelled = save(TargetType.REGION, region.getId(),
                Instant.parse("2026-06-05T08:00:00Z"), Instant.parse("2026-06-05T20:00:00Z"));
        softDeleteAt(cancelled.getId(), Instant.parse("2026-06-05T10:30:00Z"));

        assertTrue(ids().contains(cancelled.getId()),
                "a campaign cancelled mid-window still owns the plays it was live for");
    }

    @Test
    void dropsACampaignDeletedBeforeTheWindow() {
        var gone = save(TargetType.REGION, region.getId(),
                Instant.parse("2026-06-05T08:00:00Z"), Instant.parse("2026-06-05T20:00:00Z"));
        softDeleteAt(gone.getId(), Instant.parse("2026-06-05T09:00:00Z"));

        assertFalse(ids().contains(gone.getId()));
    }

    @Test
    void dropsDraftsAndWindowsThatMissTheBatch() {
        var draft = assignmentRepository.save(new ContentAssignment(playlist, TargetType.REGION,
                region.getId(), Instant.parse("2026-06-05T08:00:00Z"),
                Instant.parse("2026-06-05T20:00:00Z"), ContentAssignment.Status.DRAFT));
        var ended = save(TargetType.REGION, region.getId(),
                Instant.parse("2026-06-05T06:00:00Z"), Instant.parse("2026-06-05T09:00:00Z"));
        var notYet = save(TargetType.REGION, region.getId(),
                Instant.parse("2026-06-05T12:00:00Z"), Instant.parse("2026-06-05T20:00:00Z"));

        var found = ids();
        assertFalse(found.contains(draft.getId()), "a DRAFT never drove a device");
        assertFalse(found.contains(ended.getId()));
        assertFalse(found.contains(notYet.getId()));
    }

    @Test
    void matchesTheDevicesRegionFacilityAndGroup_butNoOneElses() {
        var byRegion = save(TargetType.REGION, region.getId(), from, to.plusSeconds(3600));
        var byFacility = save(TargetType.FACILITY, facility.getId(), from, to.plusSeconds(3600));
        var byGroup = save(TargetType.DEVICE_GROUP, deviceGroup.getId(), from, to.plusSeconds(3600));
        var elsewhere = save(TargetType.REGION, region.getId() + 999L, from, to.plusSeconds(3600));

        var found = ids();
        assertTrue(found.contains(byRegion.getId()));
        assertTrue(found.contains(byFacility.getId()));
        assertTrue(found.contains(byGroup.getId()));
        assertFalse(found.contains(elsewhere.getId()));
        assertEquals(3, found.size());
    }

    @Test
    void anUnplacedDeviceMatchesOnRegionAlone() {
        // A device with no facility and no group passes nulls; those branches must simply not
        // match instead of erroring or (worse) matching everything.
        var byRegion = save(TargetType.REGION, region.getId(), from, to.plusSeconds(3600));
        save(TargetType.FACILITY, facility.getId(), from, to.plusSeconds(3600));
        save(TargetType.DEVICE_GROUP, deviceGroup.getId(), from, to.plusSeconds(3600));

        var found = assignmentRepository
                .findHistoricalCandidates(region.getId(), null, null, from, to)
                .stream().map(ContentAssignment::getId).toList();

        assertEquals(List.of(byRegion.getId()), found);
    }

    private ContentAssignment save(TargetType type, Long targetId, Instant start, Instant end) {
        return assignmentRepository.save(new ContentAssignment(playlist, type, targetId, start, end));
    }

    private List<Long> ids() {
        return assignmentRepository
                .findHistoricalCandidates(region.getId(), facility.getId(), deviceGroup.getId(), from, to)
                .stream().map(ContentAssignment::getId).toList();
    }

    /**
     * Soft-delete as the application does. Bound as an {@code OffsetDateTime}, never as a bare
     * {@code TIMESTAMP 'yyyy-MM-dd HH:mm:ss'} literal: H2 reads that in the session's zone, so on
     * a machine east of UTC the row lands hours away from where the test meant to put it.
     */
    private void softDeleteAt(Long assignmentId, Instant deletedAt) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE content_assignment SET deleted_at = ? WHERE id = ?")) {
            ps.setObject(1, deletedAt.atOffset(ZoneOffset.UTC));
            ps.setLong(2, assignmentId);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("soft delete " + assignmentId, e);
        }
    }
}
