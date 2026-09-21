package uz.orientadvertise.services.infra.repository;

import java.sql.Connection;
import java.sql.Statement;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentAssignment.TargetType;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceStatusView;
import uz.orientadvertise.services.domain.repository.ContentAssignmentExclusionRepository;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.repository.RegionRepository;
import uz.orientadvertise.services.infra.TestApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the END STATE a window-scoped REPLACE leaves behind (LOGIC-05, v1.0.142) against the REAL
 * JPQL and the REAL {@code device_status_view} on H2 (PostgreSQL mode) under the full Flyway
 * schema. Sibling of {@link ContentAssignmentPartialSupersedeTest}, which pins the device-scoped
 * half of the same decision.
 *
 * <p>Scenario: region {@code R}, one device, driven by an open-ended booking P (playlist
 * "Korzinka promo", window {@code [yesterday, 2100)}). The operator confirms a ONE-WEEK campaign C
 * (playlist "Yangi reklama") with Replace. Because C's window ends before P's, {@code supersede}
 * leaves P completely untouched — both rows stay CONFIRMED and overlapping, and precedence decides
 * who plays:
 *
 * <pre>
 *   before C   during C   after C
 *      P          C          P      ← what the device must resolve at each instant
 * </pre>
 *
 * <p>Module boundary: {@code infra} cannot depend on {@code service}, so the confirm is reproduced
 * here as the rows it commits and the resolution is reproduced as the exact query pair
 * {@code ContentAssignmentService.resolveForDevice} composes — {@code findActiveAtTime} plus
 * {@code ContentAssignmentExclusionRepository.findByDeviceId}, {@code max(PRECEDENCE)}. That is the
 * point: the SAME comparator production uses, over rows the real query returns.
 *
 * <p>{@code device_status_view} is checked as well. Its ORDER BY is a FOURTH copy of the same rule,
 * and it is the copy the operator console reads — if it and the JPQL disagree, the device plays one
 * playlist while the list claims another, with nothing failing anywhere.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
class ContentAssignmentWindowOverrideTest {

    @Autowired private DataSource dataSource;
    @Autowired private ContentAssignmentRepository assignmentRepository;
    @Autowired private ContentAssignmentExclusionRepository exclusionRepository;
    @Autowired private PlaylistRepository playlistRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private RegionRepository regionRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private DeviceStatusViewRepository statusViewRepository;

    /** P: yesterday → "forever" (the year-2100 sentinel the FE sends for "no end date"). */
    private final Instant predecessorStart = Instant.parse("2026-06-01T00:00:00Z");
    private final Instant forever = Instant.parse("2100-01-01T00:00:00Z");
    /** C: a one-week campaign inside P's window. */
    private final Instant campaignStart = Instant.parse("2026-06-02T00:00:00Z");
    private final Instant campaignEnd = Instant.parse("2026-06-09T00:00:00Z");

    private final Instant beforeCampaign = Instant.parse("2026-06-01T12:00:00Z");
    private final Instant duringCampaign = Instant.parse("2026-06-05T12:00:00Z");
    private final Instant afterCampaign = Instant.parse("2026-06-20T12:00:00Z");

    private Device device;
    private Long bookingPlaylistId;
    private Long campaignPlaylistId;
    private ContentAssignment booking;
    private ContentAssignment campaign;

    @BeforeEach
    void setUp() {
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        var project = projectRepository.save(new uz.orientadvertise.services.domain.model.Project(
                "WindowOverrideProj", null));
        var region = regionRepository.save(new uz.orientadvertise.services.domain.model.Region(
                project, "Toshkent", "tsh"));
        var bookingPlaylist = playlistRepository.save(new uz.orientadvertise.services.domain.model.Playlist(
                project, "Korzinka promo", null));
        var campaignPlaylist = playlistRepository.save(new uz.orientadvertise.services.domain.model.Playlist(
                project, "Yangi reklama", null));
        bookingPlaylistId = bookingPlaylist.getId();
        campaignPlaylistId = campaignPlaylist.getId();

        device = deviceRepository.save(new Device(region, null, "SN-WINDOW", "Window"));

        // The open-ended booking, confirmed FIRST. Saved as CONFIRMED with an explicit
        // confirmedAt — the constructor's status is CONFIRMED but confirm() is what stamps the
        // instant, and this fixture must control the precedence order, not race the clock.
        booking = assignmentRepository.save(new ContentAssignment(
                bookingPlaylist, TargetType.REGION, region.getId(), predecessorStart, forever));
        // The campaign, confirmed SECOND — it wins the overlap on recency.
        campaign = assignmentRepository.save(new ContentAssignment(
                campaignPlaylist, TargetType.REGION, region.getId(), campaignStart, campaignEnd));
        stampConfirmedAt(booking.getId(), "2026-05-01 00:00:00");
        stampConfirmedAt(campaign.getId(), "2026-06-01 09:00:00");

        // The device must look ONLINE to device_status_view (its computed_status is heartbeat-
        // derived and gates nothing here, but a NULL beat reads OFFLINE and muddies the row).
        exec("UPDATE device SET last_heartbeat_at = CURRENT_TIMESTAMP WHERE id = " + device.getId());
    }

    private void stampConfirmedAt(Long assignmentId, String literal) {
        exec("UPDATE content_assignment SET confirmed_at = TIMESTAMP '" + literal + "' WHERE id = "
                + assignmentId);
    }

    private void exec(String sql) {
        try (Connection conn = dataSource.getConnection(); Statement s = conn.createStatement()) {
            s.execute(sql);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /** The exact resolution {@code ContentAssignmentService.resolveForDevice} performs. */
    private ContentAssignment resolve(Instant atTime) {
        Set<Long> excluded = exclusionRepository.findByDeviceId(device.getId()).stream()
                .map(e -> e.getAssignment().getId())
                .collect(Collectors.toSet());
        return assignmentRepository.findActiveAtTime(atTime).stream()
                .filter(a -> !excluded.contains(a.getId()))
                .filter(a -> a.getTargetType() == TargetType.REGION
                        && a.getTargetId().equals(device.getRegion().getId()))
                .max(ContentAssignment.PRECEDENCE)
                .orElse(null);
    }

    private List<Long> activeIds(Instant atTime) {
        return assignmentRepository.findActiveAtTime(atTime).stream().map(ContentAssignment::getId).toList();
    }

    /**
     * The state v1.0.142 relies on must be REPRESENTABLE and VISIBLE: two CONFIRMED, non-deleted,
     * overlapping assignments on one target persist (no constraint rejects them, unlike the
     * "one live remote session per device" rule V43 could not express), and the production queries
     * that ask about them answer truthfully. This is the schema/query side of the decision — the
     * decision itself ({@code supersede} leaving the booking alone) is pinned in
     * {@code ContentAssignmentServiceTest}, since {@code infra} cannot call the service.
     */
    @Test
    void overlappingConfirmedPairIsAValidPersistedState_andTheQueriesReportIt() {
        var reloaded = assignmentRepository.findById(booking.getId()).orElseThrow();
        assertFalse(reloaded.isDeleted(), "the DB keeps both rows live");
        assertEquals(ContentAssignment.Status.CONFIRMED, reloaded.getStatus(),
                "CONFIRMED, so playlist/device-group/facility deletes still 409 on it");
        assertEquals(forever, reloaded.getEndTime(), "full window, un-truncated");
        assertEquals(List.of(), exclusionRepository.findDeviceIdsByAssignmentId(booking.getId()),
                "no exclusion rows — they are permanent and would stop it resuming");

        // countActiveAssignmentsByPlaylistId drives the DELETE /api/playlists/{id} 409 guard: the
        // outranked booking must still count, or its playlist could be deleted out from under it.
        assertEquals(1, assignmentRepository.countActiveAssignmentsByPlaylistId(bookingPlaylistId),
                "an outranked-but-live assignment still blocks its playlist's delete");

        // findOverlappingExcluding is the confirm-time gate: each row sees the other as a conflict,
        // so a THIRD assignment over the same window is still refused (or offered Replace).
        var seenFromCampaign = assignmentRepository.findOverlappingExcluding(
                TargetType.REGION, device.getRegion().getId(), campaignStart, campaignEnd, campaign.getId());
        assertEquals(List.of(booking.getId()),
                seenFromCampaign.stream().map(ContentAssignment::getId).toList(),
                "the confirm gate still sees the booking under the campaign");
    }

    @Test
    void beforeTheCampaign_theBookingPlays() {
        assertEquals(List.of(booking.getId()), activeIds(beforeCampaign),
                "the campaign has not started — only the booking is in window");
        assertEquals(bookingPlaylistId, resolve(beforeCampaign).getPlaylist().getId());
    }

    @Test
    void duringTheCampaign_bothAreActive_andTheCampaignWins() {
        // BOTH rows are returned: the overlap is real and deliberate, not a data error.
        assertEquals(List.of(campaign.getId(), booking.getId()), activeIds(duringCampaign),
                "findActiveAtTime returns both, WINNER FIRST (priority, then recency, then id)");
        assertEquals(campaignPlaylistId, resolve(duringCampaign).getPlaylist().getId(),
                "the most recently confirmed assignment drives the device during the overlap");
    }

    @Test
    void afterTheCampaign_theBookingResumesByItself() {
        // THE BUG: pre-v1.0.142 the booking was retired at confirm time, so this resolved to null,
        // the screen went blank and /sync told the device to purge its files.
        assertEquals(List.of(booking.getId()), activeIds(afterCampaign),
                "the campaign's window has closed; the booking is still there");
        var resolved = resolve(afterCampaign);
        assertNotNull(resolved, "the device must NOT go dark when the campaign ends");
        assertEquals(bookingPlaylistId, resolved.getPlaylist().getId(),
                "the booking resumes with no operator action");
    }

    @Test
    void deviceStatusView_agreesWithTheResolver_onTheWinnerOfTheOverlap() {
        // The view resolves against CURRENT_TIMESTAMP, so "now" is the instant under test: both
        // rows are moved to a window around now, keeping the campaign strictly inside the booking.
        exec("UPDATE content_assignment SET start_time = CURRENT_TIMESTAMP - INTERVAL '1' DAY, "
                + "end_time = TIMESTAMP '2100-01-01 00:00:00' WHERE id = " + booking.getId());
        exec("UPDATE content_assignment SET start_time = CURRENT_TIMESTAMP - INTERVAL '1' HOUR, "
                + "end_time = CURRENT_TIMESTAMP + INTERVAL '1' HOUR WHERE id = " + campaign.getId());

        var row = viewRow();
        assertEquals(campaignPlaylistId, row.getActivePlaylistId(),
                "device_status_view must pick the same winner as findActiveAtTime + PRECEDENCE");
        assertEquals("Yangi reklama", row.getActivePlaylistName(), "id and name come from ONE predicate");
        assertEquals(Device.Status.ONLINE, row.getComputedStatus());
    }

    @Test
    void deviceStatusView_afterTheCampaignsWindow_showsTheBookingAgain() {
        // Same two rows, but the campaign's window is now in the PAST — the view must fall back to
        // the booking, not report NO_CONTENT. This is the operator-console half of the bug.
        exec("UPDATE content_assignment SET start_time = CURRENT_TIMESTAMP - INTERVAL '10' DAY, "
                + "end_time = TIMESTAMP '2100-01-01 00:00:00' WHERE id = " + booking.getId());
        exec("UPDATE content_assignment SET start_time = CURRENT_TIMESTAMP - INTERVAL '9' DAY, "
                + "end_time = CURRENT_TIMESTAMP - INTERVAL '2' DAY WHERE id = " + campaign.getId());

        var row = viewRow();
        assertEquals(bookingPlaylistId, row.getActivePlaylistId(),
                "the booking is the only assignment still in window");
        assertEquals(Device.Status.ONLINE, row.getComputedStatus(),
                "NOT NO_CONTENT — the device has content again");
    }

    @Test
    void deviceStatusView_tieBreakIsRecency_notId() {
        // Both windows identical, so ONLY the tie-break can decide. The booking has the LOWER id
        // but is confirmed EARLIER, so the campaign must still win — with the pre-V48
        // "priority DESC, id DESC" the campaign wins for the wrong reason, so invert the ids too.
        exec("UPDATE content_assignment SET start_time = CURRENT_TIMESTAMP - INTERVAL '1' DAY, "
                + "end_time = CURRENT_TIMESTAMP + INTERVAL '1' DAY WHERE id IN ("
                + booking.getId() + ", " + campaign.getId() + ")");
        // Make the WINNER the lower id: swap the confirmed_at stamps instead of the ids, so the
        // higher-id row (the campaign) is the STALE one and the booking must win on recency.
        stampConfirmedAt(booking.getId(), "2026-06-02 00:00:00");
        stampConfirmedAt(campaign.getId(), "2026-05-01 00:00:00");

        var row = viewRow();
        assertEquals(bookingPlaylistId, row.getActivePlaylistId(),
                "id DESC would pick the campaign (higher id); recency must pick the booking");
        assertTrue(booking.getId() < campaign.getId(), "the fixture really does run against id DESC");
        // …and the JPQL + comparator must reach the same conclusion.
        assertEquals(bookingPlaylistId,
                resolve(Instant.now()).getPlaylist().getId(),
                "Java and SQL must never disagree about the winner");
    }

    private DeviceStatusView viewRow() {
        var page = statusViewRepository.findFiltered(null, null, null, null, null, null, null, null,
                null, null, null, null, PageRequest.of(0, 50));
        return page.getContent().stream()
                .filter(v -> "SN-WINDOW".equals(v.getSerialNumber()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("device row missing from device_status_view"));
    }
}
