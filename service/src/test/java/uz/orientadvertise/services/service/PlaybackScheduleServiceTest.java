package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import uz.orientadvertise.services.domain.model.PlaybackSyncSchedule;
import uz.orientadvertise.services.domain.repository.PlaybackSyncScheduleRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaybackScheduleServiceTest {

    private PlaybackSyncScheduleRepository repository;
    private PlaybackScheduleWriter writer;
    private PlaybackScheduleService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlaybackSyncScheduleRepository.class);
        writer = mock(PlaybackScheduleWriter.class);
        service = new PlaybackScheduleService(repository, writer, Duration.ofMinutes(2));
    }

    @Test
    void getOrCreate_existingRow_returnsIt_withoutWriting() {
        var existing = new PlaybackSyncSchedule(300L, 1, "v-a", Instant.ofEpochMilli(1_000_000L));
        when(repository.findByAssignmentIdAndVersionNumber(300L, 1)).thenReturn(Optional.of(existing));

        var result = service.getOrCreate(300L, 1, "v-a");

        assertSame(existing, result);
        verify(writer, never()).insertIfAbsent(any(), anyInt(), any(), any());
    }

    @Test
    void getOrCreate_absent_delegatesToWriter_withConfiguredLead() {
        var created = new PlaybackSyncSchedule(300L, 1, "v-a", Instant.ofEpochMilli(2_000_000L));
        when(repository.findByAssignmentIdAndVersionNumber(300L, 1)).thenReturn(Optional.empty());
        when(writer.insertIfAbsent(eq(300L), eq(1), eq("v-a"), any())).thenReturn(created);

        var result = service.getOrCreate(300L, 1, "v-a");

        assertSame(created, result);
        verify(writer).insertIfAbsent(300L, 1, "v-a", Duration.ofMinutes(2));
    }

    @Test
    void getOrCreate_isStablePerAssignmentVersion_anchorNeverMoves() {
        // First call anchors (absent → write); every later call reads the SAME immutable row.
        var row = new PlaybackSyncSchedule(300L, 1, "v-a", Instant.ofEpochMilli(1_719_830_400_000L));
        when(repository.findByAssignmentIdAndVersionNumber(300L, 1))
                .thenReturn(Optional.empty())     // 1st call: not yet anchored
                .thenReturn(Optional.of(row))     // 2nd call: read the created row
                .thenReturn(Optional.of(row));    // 3rd call: still the same row
        when(writer.insertIfAbsent(eq(300L), eq(1), eq("v-a"), any())).thenReturn(row);

        long a1 = service.getOrCreate(300L, 1, "v-a").getAnchorEpochMs();
        long a2 = service.getOrCreate(300L, 1, "v-a").getAnchorEpochMs();
        long a3 = service.getOrCreate(300L, 1, "v-a").getAnchorEpochMs();

        assertEquals(1_719_830_400_000L, a1);
        assertEquals(a1, a2, "anchor must not move once set");
        assertEquals(a2, a3, "anchor must not move once set");
        verify(writer, times(1)).insertIfAbsent(any(), anyInt(), any(), any());
    }

    @Test
    void getOrCreate_lostCreateRace_reReadsWinnersRow() {
        var winner = new PlaybackSyncSchedule(300L, 1, "v-a", Instant.ofEpochMilli(5_000_000L));
        when(repository.findByAssignmentIdAndVersionNumber(300L, 1))
                .thenReturn(Optional.empty())        // initial check: absent
                .thenReturn(Optional.of(winner));    // post-violation re-read: winner committed
        when(writer.insertIfAbsent(eq(300L), eq(1), eq("v-a"), any()))
                .thenThrow(new DataIntegrityViolationException("uq_playback_sched"));

        var result = service.getOrCreate(300L, 1, "v-a");

        assertSame(winner, result,
                "a lost creation race re-reads the winner's row so the group shares exactly one anchor");
    }

    @Test
    void entity_anchorEqualsActivate_byConstruction() {
        var row = new PlaybackSyncSchedule(1L, 1, "v", Instant.ofEpochMilli(1_234_567_000L));
        assertEquals(1_234_567_000L, row.getAnchorEpochMs());
        assertEquals(row.getActivateAtEpochMs(), row.getAnchorEpochMs(),
                "anchor is the cut-over instant (loop T0)");
    }
}
