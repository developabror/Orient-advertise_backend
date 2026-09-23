package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.PlaybackSyncSchedule;
import uz.orientadvertise.services.domain.repository.PlaybackSyncScheduleRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaybackScheduleServiceTest {

    private static final Duration MIN_LEAD = Duration.ofMinutes(2);
    private static final Duration MAX_LEAD = Duration.ofMinutes(15);
    private static final long BYTES_PER_SEC = 1_048_576L;   // 1 MB/s, the shipped default

    private PlaybackSyncScheduleRepository repository;
    private PlaybackScheduleService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlaybackSyncScheduleRepository.class);
        service = new PlaybackScheduleService(repository, MIN_LEAD, MAX_LEAD, BYTES_PER_SEC);
    }

    @Test
    void getOrCreate_existingRow_returnsIt_withoutWriting() {
        var existing = new PlaybackSyncSchedule(300L, 1, "v-a", Instant.ofEpochMilli(1_000_000L));
        when(repository.findByAssignmentIdAndContentVersion(300L, "v-a")).thenReturn(Optional.of(existing));

        var result = service.getOrCreate(300L, 1, "v-a", 0L);

        assertSame(existing, result);
        verify(repository, never()).insertIfAbsent(any(), anyInt(), anyString(), anyLong(), any(), any());
    }

    @Test
    void getOrCreate_absent_insertsThenReadsBack() {
        var created = new PlaybackSyncSchedule(300L, 1, "v-a", Instant.ofEpochMilli(2_000_000L));
        when(repository.findByAssignmentIdAndContentVersion(300L, "v-a"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(created));
        when(repository.insertIfAbsent(eq(300L), eq(1), eq("v-a"), anyLong(), any(), any())).thenReturn(1);

        var result = service.getOrCreate(300L, 1, "v-a", 0L);

        assertSame(created, result);
    }

    @Test
    void getOrCreate_isKeyedOnTheContentVersion_soAnEditGetsItsOwnAnchor() {
        // VG-06: version_number never moves (bumpVersion has no callers), so keying on it meant a
        // playlist edit reused the ORIGINAL anchor — a cut-over instant already in the past.
        var edited = new PlaybackSyncSchedule(300L, 1, "v-b", Instant.ofEpochMilli(9_000_000L));
        when(repository.findByAssignmentIdAndContentVersion(300L, "v-a"))
                .thenReturn(Optional.of(new PlaybackSyncSchedule(300L, 1, "v-a", Instant.ofEpochMilli(1_000L))));
        when(repository.findByAssignmentIdAndContentVersion(300L, "v-b"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(edited));
        when(repository.insertIfAbsent(eq(300L), eq(1), eq("v-b"), anyLong(), any(), any())).thenReturn(1);

        var before = service.getOrCreate(300L, 1, "v-a", 0L);
        var after = service.getOrCreate(300L, 1, "v-b", 0L);

        assertEquals(1_000L, before.getAnchorEpochMs());
        assertEquals(9_000_000L, after.getAnchorEpochMs(), "the edited content anchors on its own row");
    }

    @Test
    void getOrCreate_isStablePerContentVersion_anchorNeverMoves() {
        var row = new PlaybackSyncSchedule(300L, 1, "v-a", Instant.ofEpochMilli(1_719_830_400_000L));
        when(repository.findByAssignmentIdAndContentVersion(300L, "v-a"))
                .thenReturn(Optional.empty())     // 1st call: not yet anchored
                .thenReturn(Optional.of(row));    // every later read: the same row
        when(repository.insertIfAbsent(eq(300L), eq(1), eq("v-a"), anyLong(), any(), any())).thenReturn(1);

        long a1 = service.getOrCreate(300L, 1, "v-a", 0L).getAnchorEpochMs();
        long a2 = service.getOrCreate(300L, 1, "v-a", 0L).getAnchorEpochMs();
        long a3 = service.getOrCreate(300L, 1, "v-a", 0L).getAnchorEpochMs();

        assertEquals(1_719_830_400_000L, a1);
        assertEquals(a1, a2, "anchor must not move once set");
        assertEquals(a2, a3, "anchor must not move once set");
        verify(repository, times(1)).insertIfAbsent(any(), anyInt(), anyString(), anyLong(), any(), any());
    }

    @Test
    void getOrCreate_lostCreateRace_reReadsWinnersRow() {
        // ON CONFLICT DO NOTHING: the loser inserts 0 rows and raises nothing, so the caller's
        // /sync transaction stays usable — the whole point of dropping the REQUIRES_NEW writer.
        var winner = new PlaybackSyncSchedule(300L, 1, "v-a", Instant.ofEpochMilli(5_000_000L));
        when(repository.findByAssignmentIdAndContentVersion(300L, "v-a"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.insertIfAbsent(eq(300L), eq(1), eq("v-a"), anyLong(), any(), any())).thenReturn(0);

        var result = service.getOrCreate(300L, 1, "v-a", 0L);

        assertSame(winner, result,
                "a lost creation race re-reads the winner's row so the group shares exactly one anchor");
    }

    @Test
    void lead_isTheMinimumWhenNothingHasToBeDownloaded() {
        // A reorder or dwell edit ships no bytes: the floor is what stops it cutting over instantly.
        assertEquals(MIN_LEAD, service.leadFor(0L));
        assertEquals(MIN_LEAD, service.leadFor(-1L));
        assertEquals(MIN_LEAD, service.leadFor(1_024L));
    }

    @Test
    void lead_growsWithTheDownload_andIsCappedAtTheReadinessWindow() {
        // 200 MB at 1 MB/s x1.5 safety = 300 s, comfortably inside the cap.
        assertEquals(Duration.ofSeconds(300), service.leadFor(200L * 1024 * 1024));
        // 2 GB would ask for over an hour; the cap keeps the cut-over inside the window the
        // readiness poll reports on.
        assertEquals(MAX_LEAD, service.leadFor(2L * 1024 * 1024 * 1024));
    }

    @Test
    void anchoredActivateAt_isInTheFuture_forAnEditThatDownloadsNothing() {
        when(repository.findByAssignmentIdAndContentVersion(300L, "v-b"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new PlaybackSyncSchedule(300L, 1, "v-b", Instant.now().plus(MIN_LEAD))));
        var activateAt = org.mockito.ArgumentCaptor.forClass(Instant.class);
        when(repository.insertIfAbsent(eq(300L), eq(1), eq("v-b"), anyLong(), activateAt.capture(), any()))
                .thenReturn(1);

        service.getOrCreate(300L, 1, "v-b", 0L);

        // The bug in one assertion: an edit used to reuse an anchor whose instant had long passed.
        assertTrue(activateAt.getValue().isAfter(Instant.now()),
                "an edit's cut-over must be in the future so every screen flips together");
    }

    @Test
    void entity_anchorEqualsActivate_byConstruction() {
        var row = new PlaybackSyncSchedule(1L, 1, "v", Instant.ofEpochMilli(1_234_567_000L));
        assertEquals(1_234_567_000L, row.getAnchorEpochMs());
        assertEquals(row.getActivateAtEpochMs(), row.getAnchorEpochMs(),
                "anchor is the cut-over instant (loop T0)");
    }
}
