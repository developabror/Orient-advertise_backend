package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.PlaybackLog;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.service.PlaybackLogService.PlaybackEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaybackLogBatchTest {

    private PlaybackLogRepository repository;
    private DeviceRepository deviceRepository;
    private ContentFileRepository contentFileRepository;
    private ContentAssignmentService assignmentService;
    private PlaylistItemRepository playlistItemRepository;
    private PlaybackLogService service;
    private Device device;
    private ContentFile contentFile;

    @BeforeEach
    void setUp() {
        repository = mock(PlaybackLogRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        contentFileRepository = mock(ContentFileRepository.class);
        assignmentService = mock(ContentAssignmentService.class);
        playlistItemRepository = mock(PlaylistItemRepository.class);
        service = new PlaybackLogService(repository, deviceRepository, contentFileRepository,
                assignmentService, playlistItemRepository, 30);

        device = mock(Device.class);
        when(device.getId()).thenReturn(1L);
        contentFile = mock(ContentFile.class);
        when(contentFile.getId()).thenReturn(10L);

        when(deviceRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(device));
        when(contentFileRepository.findById(10L)).thenReturn(Optional.of(contentFile));
        when(repository.save(any(PlaybackLog.class))).thenAnswer(inv -> inv.getArgument(0));
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
        // Device is assigned a playlist containing only content 10. An entry reporting
        // playback of content 99 (not in the playlist) is rejected as analytics-forgery,
        // while the in-playlist entry is accepted.
        var playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(500L);
        var assignment = mock(ContentAssignment.class);
        when(assignment.getPlaylist()).thenReturn(playlist);
        when(assignmentService.resolveForDevice(any(), any())).thenReturn(assignment);
        var item = mock(PlaylistItem.class);
        when(item.getContentFile()).thenReturn(contentFile); // content id 10
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(500L)).thenReturn(List.of(item));

        var other = mock(ContentFile.class);
        when(other.getId()).thenReturn(99L);
        when(contentFileRepository.findById(99L)).thenReturn(Optional.of(other));

        var now = Instant.now();
        var result = service.recordBatch(1L, List.of(
                new PlaybackEntry(10L, now, 30),   // assigned → accepted
                new PlaybackEntry(99L, now, 30))); // not assigned → rejected

        assertEquals(1, result.created());
        assertEquals(1, result.rejected());
        assertTrue(result.rejections().stream().anyMatch(r -> r.reason().contains("not assigned")));
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
        verify(repository, times(500)).save(any(PlaybackLog.class));
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
        // First save succeeds, second hits unique constraint
        when(repository.save(any(PlaybackLog.class)))
                .thenAnswer(inv -> inv.getArgument(0))
                .thenThrow(new DataIntegrityViolationException("uq_playback_dedup violated"));

        Instant t1 = Instant.now().minusSeconds(60);
        var entries = List.of(
                new PlaybackEntry(10L, t1, 30),
                new PlaybackEntry(10L, t1, 30)); // same key — duplicate

        var result = service.recordBatch(1L, entries);

        assertEquals(1, result.created());
        assertEquals(1, result.duplicate());
        assertEquals(0, result.rejected());
    }

    @Test
    void mixedBatch_validInvalidDuplicate_independentlyTallied() {
        when(repository.save(any(PlaybackLog.class)))
                .thenAnswer(inv -> inv.getArgument(0))                                // 1 created
                .thenAnswer(inv -> inv.getArgument(0))                                // 2 created
                .thenThrow(new DataIntegrityViolationException("uq_playback_dedup")); // 3 duplicate

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
