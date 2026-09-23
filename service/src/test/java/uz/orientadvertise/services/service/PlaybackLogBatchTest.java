package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.model.ContentAssignmentExclusion;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.ContentAssignmentExclusionRepository;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.service.PlaybackLogService.PlaybackEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaybackLogBatchTest {

    /** Matches the shipped default (`app.playback.assignment-grace`). */
    private static final Duration GRACE = Duration.ofMinutes(30);

    private PlaybackLogRepository repository;
    private DeviceRepository deviceRepository;
    private ContentFileRepository contentFileRepository;
    private ContentAssignmentRepository assignmentRepository;
    private ContentAssignmentExclusionRepository exclusionRepository;
    private PlaylistItemRepository playlistItemRepository;
    private PlaybackLogService service;
    private Device device;
    private ContentFile contentFile;

    @BeforeEach
    void setUp() {
        repository = mock(PlaybackLogRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        contentFileRepository = mock(ContentFileRepository.class);
        assignmentRepository = mock(ContentAssignmentRepository.class);
        exclusionRepository = mock(ContentAssignmentExclusionRepository.class);
        playlistItemRepository = mock(PlaylistItemRepository.class);
        service = new PlaybackLogService(repository, deviceRepository, contentFileRepository,
                assignmentRepository, exclusionRepository, playlistItemRepository,
                new RetentionProperties(), 30, GRACE);

        device = mock(Device.class);
        when(device.getId()).thenReturn(1L);
        var region = mock(Region.class);
        when(region.getId()).thenReturn(7L);
        when(device.getRegion()).thenReturn(region);
        contentFile = mock(ContentFile.class);
        when(contentFile.getId()).thenReturn(10L);

        when(deviceRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(device));
        when(contentFileRepository.findById(10L)).thenReturn(Optional.of(contentFile));

        // DANGER: insertIgnoringDuplicate returns a primitive int, so an UNSTUBBED mock returns
        // 0 — which the service reads as "duplicate". Every Created assertion would silently
        // flip green-but-wrong. Stub it key-aware so dedup is simulated by (contentFileId,
        // playedAt) rather than by call order: 1 the first time a key is seen, 0 afterwards.
        var seen = new HashSet<List<Object>>();
        when(repository.insertIgnoringDuplicate(anyLong(), anyLong(), nullable(Long.class),
                any(Instant.class), nullable(Integer.class), any(Instant.class)))
                .thenAnswer(inv -> seen.add(List.of(inv.getArgument(1), inv.getArgument(3))) ? 1 : 0);
    }

    @Test
    void emptyBatch_returnsZeroCounts() {
        var result = service.recordBatch(1L, List.of());
        assertEquals(0, result.created());
        assertEquals(0, result.duplicate());
        assertEquals(0, result.rejected());
    }

    @Test
    void nullBatch_returnsZeroCounts() {
        var result = service.recordBatch(1L, null);
        assertEquals(0, result.created());
    }

    @Test
    void batchOver500_throws400() {
        // Edge case requirement: batch limit 500 events.
        var entries = new ArrayList<PlaybackEntry>();
        for (int i = 0; i < 501; i++) {
            entries.add(new PlaybackEntry(10L, Instant.now().minusSeconds(i + 1), 30));
        }

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.recordBatch(1L, entries));
        assertTrue(ex.getMessage().contains("500"));
    }

    @Test
    void recordBatch_rejectsContentNotInAssignedPlaylist() {
        // Device plays a playlist containing only content 10. An entry claiming content 99 is
        // rejected as analytics-forgery, while the in-playlist entry is accepted and attributed.
        var now = Instant.now();
        var assignment = confirmedAssignment(700L, 500L, now.minus(Duration.ofHours(2)),
                now.plus(Duration.ofHours(2)), now.minus(Duration.ofHours(2)));
        stubCandidates(assignment);
        stubPlaylistFiles(500L, 10L);
        stubContentFile(99L);

        var result = service.recordBatch(1L, List.of(
                new PlaybackEntry(10L, now, 30),   // assigned → accepted
                new PlaybackEntry(99L, now, 30))); // not assigned → rejected

        assertEquals(1, result.created());
        assertEquals(1, result.rejected());
        assertTrue(result.rejections().stream().anyMatch(r -> r.reason().contains("not assigned")));
        assertEquals(List.of(700L), attributedAssignmentIds());
    }

    // ----- VG-03: a play belongs to the campaign that was live when it PLAYED -----

    @Test
    void playsAroundACampaignSwitch_areKeptAndCreditedToTheRightCampaign() {
        // THE BUG REPRO. Campaign A (file 10) is replaced by B (file 99) at T. The device keeps
        // playing A's clip for a couple of minutes — it has to download B first — then flushes
        // everything at once. The old code checked every entry against the campaign live NOW, so
        // both of A's plays were thrown away and nothing was attributed: an advertiser undercount.
        var now = Instant.now();
        var switchAt = now.minus(Duration.ofHours(3));
        var a = confirmedAssignment(100L, 500L, switchAt.minus(Duration.ofHours(2)), switchAt,
                switchAt.minus(Duration.ofHours(2)));           // truncated by the Replace
        var b = confirmedAssignment(200L, 600L, switchAt, now.plus(Duration.ofHours(1)), switchAt);
        stubCandidates(a, b);
        stubPlaylistFiles(500L, 10L);
        stubPlaylistFiles(600L, 99L);
        stubContentFile(99L);

        var result = service.recordBatch(1L, List.of(
                new PlaybackEntry(10L, switchAt.minus(Duration.ofMinutes(10)), 30),  // A was live
                new PlaybackEntry(10L, switchAt.plus(Duration.ofMinutes(2)), 30),    // still on A
                new PlaybackEntry(99L, switchAt.plus(Duration.ofMinutes(5)), 30)));  // switched

        assertEquals(3, result.created());
        assertEquals(0, result.rejected());
        assertEquals(List.of(100L, 100L, 200L), attributedAssignmentIds());
    }

    @Test
    void aPlayLongAfterTheSwitch_isStillRejected() {
        // The grace window is not a free pass: hours later the device cannot honestly still be
        // playing the old campaign, so a claim for its clip is forgery again.
        var now = Instant.now();
        var switchAt = now.minus(Duration.ofHours(3));
        var a = confirmedAssignment(100L, 500L, switchAt.minus(Duration.ofHours(2)), switchAt,
                switchAt.minus(Duration.ofHours(2)));
        var b = confirmedAssignment(200L, 600L, switchAt, now.plus(Duration.ofHours(1)), switchAt);
        stubCandidates(a, b);
        stubPlaylistFiles(500L, 10L);
        stubPlaylistFiles(600L, 99L);

        var result = service.recordBatch(1L, List.of(
                new PlaybackEntry(10L, switchAt.plus(Duration.ofHours(2)), 30)));

        assertEquals(0, result.created());
        assertEquals(1, result.rejected());
    }

    @Test
    void aPlayFromBeforeACancel_isKept() {
        // Cancelling soft-deletes the row, and the live resolver filters those out — so without
        // history the device's last flush of a cancelled campaign would be discarded wholesale.
        var now = Instant.now();
        var cancelledAt = now.minus(Duration.ofMinutes(20));
        var cancelled = confirmedAssignment(300L, 500L, now.minus(Duration.ofHours(4)),
                now.plus(Duration.ofHours(4)), now.minus(Duration.ofHours(4)));
        setField(cancelled, "deletedAt", cancelledAt);
        stubCandidates(cancelled);
        stubPlaylistFiles(500L, 10L);

        var result = service.recordBatch(1L, List.of(
                new PlaybackEntry(10L, cancelledAt.minus(Duration.ofMinutes(1)), 30)));

        assertEquals(1, result.created());
        assertEquals(List.of(300L), attributedAssignmentIds());
    }

    @Test
    void aPlayFromBeforeAnExclusion_isKept_andOneLongAfterIsNot() {
        // Taking a device off an assignment (a partial-device Replace writes exclusions) applies
        // from the moment it is written — it must not erase what the device played before it.
        var now = Instant.now();
        var excludedAt = now.minus(Duration.ofHours(2));
        var assignment = confirmedAssignment(400L, 500L, now.minus(Duration.ofHours(6)),
                now.plus(Duration.ofHours(6)), now.minus(Duration.ofHours(6)));
        stubCandidates(assignment);
        stubPlaylistFiles(500L, 10L);
        stubExclusion(assignment, excludedAt);

        var result = service.recordBatch(1L, List.of(
                new PlaybackEntry(10L, excludedAt.minus(Duration.ofMinutes(1)), 30),
                new PlaybackEntry(10L, excludedAt.plus(Duration.ofHours(1)), 30)));

        assertEquals(1, result.created());
        assertEquals(1, result.rejected());
        assertEquals(List.of(400L), attributedAssignmentIds());
    }

    @Test
    void whenNothingEverTargetedTheDevice_playsAreKeptUnattributed() {
        // Unchanged behaviour: with no campaign to check against we cannot validate, so the
        // report is kept rather than silently dropped — but it carries no assignment.
        var now = Instant.now();
        stubCandidates();

        var result = service.recordBatch(1L, List.of(new PlaybackEntry(10L, now, 30)));

        assertEquals(1, result.created());
        assertEquals(0, result.rejected());
        assertEquals(java.util.Collections.singletonList(null), attributedAssignmentIds());
    }

    @Test
    void theHistoryIsLoadedOncePerBatch_notPerEntry() {
        var now = Instant.now();
        var assignment = confirmedAssignment(100L, 500L, now.minus(Duration.ofHours(2)),
                now.plus(Duration.ofHours(2)), now.minus(Duration.ofHours(2)));
        stubCandidates(assignment);
        stubPlaylistFiles(500L, 10L);

        var entries = new ArrayList<PlaybackEntry>();
        for (int i = 0; i < 50; i++) {
            entries.add(new PlaybackEntry(10L, now.minusSeconds(i + 1), 30));
        }
        service.recordBatch(1L, entries);

        verify(assignmentRepository, times(1))
                .findHistoricalCandidates(any(), any(), any(), any(), any());
        verify(exclusionRepository, times(1)).findByDeviceId(1L);
        verify(playlistItemRepository, times(1)).findByPlaylistIdOrderByPositionAsc(500L);
    }

    /** A real (not mocked) assignment: {@code wasLiveDuring} is the rule under test here. */
    private static ContentAssignment confirmedAssignment(Long id, Long playlistId, Instant start,
                                                         Instant end, Instant confirmedAt) {
        var playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(playlistId);
        var assignment = new ContentAssignment(playlist, ContentAssignment.TargetType.REGION, 7L,
                start, end);
        setField(assignment, "id", id);
        setField(assignment, "createdAt", confirmedAt);
        setField(assignment, "confirmedAt", confirmedAt);
        return assignment;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void stubCandidates(ContentAssignment... assignments) {
        when(assignmentRepository.findHistoricalCandidates(any(), any(), any(), any(), any()))
                .thenReturn(List.of(assignments));
    }

    private void stubPlaylistFiles(Long playlistId, Long... fileIds) {
        var items = new ArrayList<PlaylistItem>();
        for (Long fileId : fileIds) {
            var file = mock(ContentFile.class);
            when(file.getId()).thenReturn(fileId);
            var item = mock(PlaylistItem.class);
            when(item.getContentFile()).thenReturn(file);
            items.add(item);
        }
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(playlistId))
                .thenReturn(List.copyOf(items));
    }

    private void stubExclusion(ContentAssignment assignment, Instant createdAt) {
        var exclusion = mock(ContentAssignmentExclusion.class);
        when(exclusion.getAssignment()).thenReturn(assignment);
        when(exclusion.getCreatedAt()).thenReturn(createdAt);
        when(exclusionRepository.findByDeviceId(1L)).thenReturn(List.of(exclusion));
    }

    private void stubContentFile(Long fileId) {
        var file = mock(ContentFile.class);
        when(file.getId()).thenReturn(fileId);
        when(contentFileRepository.findById(fileId)).thenReturn(Optional.of(file));
    }

    /** The assignment id written with each accepted play, in order. */
    private List<Long> attributedAssignmentIds() {
        var captor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).insertIgnoringDuplicate(
                anyLong(), anyLong(), captor.capture(), any(Instant.class), nullable(Integer.class),
                any(Instant.class));
        return captor.getAllValues();
    }

    @Test
    void batchAt500_isAccepted() {
        var entries = new ArrayList<PlaybackEntry>();
        Instant base = Instant.now().minusSeconds(60);
        for (int i = 0; i < 500; i++) {
            // Each event needs a unique playedAt to avoid hitting dedup
            entries.add(new PlaybackEntry(10L, base.minusSeconds(i), 30));
        }

        var result = service.recordBatch(1L, entries);

        assertEquals(500, result.created());
        verify(repository, times(500)).insertIgnoringDuplicate(anyLong(), anyLong(),
                nullable(Long.class), any(Instant.class), nullable(Integer.class), any(Instant.class));
    }

    @Test
    void unknownDevice_throws404() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.recordBatch(404L, List.of(
                        new PlaybackEntry(10L, Instant.now(), 30))));
    }

    @Test
    void unknownContentFile_rejectedWithReason_othersStillProcessed() {
        when(contentFileRepository.findById(99L)).thenReturn(Optional.empty());
        Instant t1 = Instant.now().minusSeconds(60);
        Instant t2 = Instant.now().minusSeconds(120);
        var entries = List.of(
                new PlaybackEntry(10L, t1, 30),  // valid
                new PlaybackEntry(99L, t2, 30)); // bad content file

        var result = service.recordBatch(1L, entries);

        assertEquals(1, result.created());
        assertEquals(1, result.rejected());
        assertEquals(1, result.rejections().size());
        assertEquals(1, result.rejections().get(0).index());
        assertTrue(result.rejections().get(0).reason().contains("contentFileId"));
    }

    @Test
    void futureTimestamp_rejectedWithReason() {
        // Edge case requirement: played_at in future = rejected.
        Instant future = Instant.now().plusSeconds(120); // beyond 30s clock skew
        var entries = List.of(new PlaybackEntry(10L, future, 30));

        var result = service.recordBatch(1L, entries);

        assertEquals(0, result.created());
        assertEquals(1, result.rejected());
        assertTrue(result.rejections().get(0).reason().contains("future"));
    }

    @Test
    void timestampOlderThan90Days_rejectedWithReason() {
        // Edge case requirement: played_at older than 90 days = rejected.
        Instant ancient = Instant.now().minus(Duration.ofDays(91));
        var entries = List.of(new PlaybackEntry(10L, ancient, 30));

        var result = service.recordBatch(1L, entries);

        assertEquals(0, result.created());
        assertEquals(1, result.rejected());
        assertTrue(result.rejections().get(0).reason().contains("90"));
    }

    @Test
    void timestampExactly90DaysOld_isAccepted() {
        // Boundary: just inside the 90-day window.
        Instant boundary = Instant.now().minus(Duration.ofDays(90)).plusSeconds(60);
        var entries = List.of(new PlaybackEntry(10L, boundary, 30));

        var result = service.recordBatch(1L, entries);

        assertEquals(1, result.created());
        assertEquals(0, result.rejected());
    }

    @Test
    void duplicate_idempotentlyDedupedNotRejected() {
        // These tallies become true of PRODUCTION for the first time: pre-fix the batch
        // transaction was already rollback-only by this point, so the counts described a
        // contract the commit could not honour.
        Instant t1 = Instant.now().minusSeconds(60);
        var entries = List.of(
                new PlaybackEntry(10L, t1, 30),
                new PlaybackEntry(10L, t1, 30)); // same key — duplicate

        var result = service.recordBatch(1L, entries);

        assertEquals(1, result.created());
        assertEquals(1, result.duplicate());
        assertEquals(0, result.rejected());
        // The in-request seen-set short-circuits the repeat, so only ONE round trip happens.
        verify(repository, times(1)).insertIgnoringDuplicate(anyLong(), anyLong(),
                nullable(Long.class), any(Instant.class), nullable(Integer.class), any(Instant.class));
    }

    @Test
    void mixedBatch_validInvalidDuplicate_independentlyTallied() {
        Instant good1 = Instant.now().minusSeconds(60);
        Instant good2 = Instant.now().minusSeconds(120);
        Instant dup = good1; // duplicate of good1
        Instant ancient = Instant.now().minus(Duration.ofDays(120));
        Instant future = Instant.now().plusSeconds(600);

        var entries = List.of(
                new PlaybackEntry(10L, good1, 30),
                new PlaybackEntry(10L, good2, 30),
                new PlaybackEntry(10L, dup, 30),
                new PlaybackEntry(10L, ancient, 30),
                new PlaybackEntry(10L, future, 30));

        var result = service.recordBatch(1L, entries);

        assertEquals(2, result.created());
        assertEquals(1, result.duplicate());
        assertEquals(2, result.rejected());
    }

    @Test
    void preexistingRowInDb_countedDuplicate_notRejected() {
        // The row already exists from an EARLIER request, so the DB skips it and reports 0
        // affected. That is a duplicate, never a rejection — the device must not be told the
        // entry was bad, or it would keep retrying.
        when(repository.insertIgnoringDuplicate(anyLong(), anyLong(), nullable(Long.class),
                any(Instant.class), nullable(Integer.class), any(Instant.class))).thenReturn(0);

        var result = service.recordBatch(1L, List.of(
                new PlaybackEntry(10L, Instant.now().minusSeconds(60), 30)));

        assertEquals(0, result.created());
        assertEquals(1, result.duplicate());
        assertEquals(0, result.rejected());
        assertTrue(result.rejections().isEmpty());
    }

    @Test
    void rejectedEntryRepeatedInSameBatch_rejectedTwiceNotDeduped() {
        // Guards the "only ACCEPTED keys enter seenInRequest" rule: a repeated entry that was
        // rejected must be rejected again, not silently reclassified as a duplicate.
        Instant future = Instant.now().plusSeconds(600); // beyond the 30s skew tolerance
        var entries = List.of(
                new PlaybackEntry(10L, future, 30),
                new PlaybackEntry(10L, future, 30));

        var result = service.recordBatch(1L, entries);

        assertEquals(0, result.created());
        assertEquals(0, result.duplicate());
        assertEquals(2, result.rejected());
        assertEquals(2, result.rejections().size());
    }

    @Test
    void contentFileLookup_cachedWithinBatch() {
        // 50 entries for the same contentFileId should hit the repo only once.
        var entries = new ArrayList<PlaybackEntry>();
        Instant base = Instant.now().minusSeconds(60);
        for (int i = 0; i < 50; i++) {
            entries.add(new PlaybackEntry(10L, base.minusSeconds(i), 30));
        }

        service.recordBatch(1L, entries);

        verify(contentFileRepository, times(1)).findById(10L);
    }
}
