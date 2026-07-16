package uz.orientadvertise.services.service;

import java.util.List;

import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.PlaylistItem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks the shared slot-timeline math that {@code /sync} and the group-jump service both rely on:
 * contiguous 0-based index, prefix-sum slotStartMs, loopDurationMs, effective-duration rule, and the
 * null/zero-duration default-dwell guard.
 */
class PlaybackSlotTimelineTest {

    @Test
    void of_prefixSumsSlotStarts_andLoopDuration() {
        var t = PlaybackSlotTimeline.of(List.of(item(0, 10L, 10, null), item(1, 20L, 20, null), item(2, 30L, 30, null)));

        assertEquals(3, t.slots().size());
        assertEquals(0L, t.slots().get(0).slotStartMs());
        assertEquals(10_000L, t.slots().get(1).slotStartMs());
        assertEquals(30_000L, t.slots().get(2).slotStartMs());
        assertEquals(10_000L, t.slots().get(0).slotDurationMs());
        assertEquals(60_000L, t.loopDurationMs());
        // contiguous 0-based index regardless of raw position
        assertEquals(0, t.slots().get(0).index());
        assertEquals(2, t.slots().get(2).index());
    }

    @Test
    void of_perItemOverrideBeatsNaturalDuration() {
        // per-item dwell = 5 overrides the file's natural 99s
        var t = PlaybackSlotTimeline.of(List.of(item(0, 10L, 99, 5)));
        assertEquals(5_000L, t.slots().get(0).slotDurationMs());
        assertEquals(Integer.valueOf(5), t.slots().get(0).effectiveSeconds());
    }

    @Test
    void of_nullEffectiveDuration_fallsBackToDefaultDwell_neverZeroSlot() {
        var t = PlaybackSlotTimeline.of(List.of(item(0, 10L, null, null)));
        assertEquals(10_000L, t.slots().get(0).slotDurationMs(), "null duration → 10s default dwell (never 0)");
        assertTrue(t.loopDurationMs() > 0);
    }

    @Test
    void of_emptyList_zeroLoop() {
        var t = PlaybackSlotTimeline.of(List.of());
        assertEquals(0, t.slots().size());
        assertEquals(0L, t.loopDurationMs());
    }

    private static PlaylistItem item(int position, long fileId, Integer naturalSeconds, Integer overrideSeconds) {
        ContentFile f = mock(ContentFile.class);
        when(f.getId()).thenReturn(fileId);
        when(f.getName()).thenReturn("file-" + fileId);
        when(f.getDurationSeconds()).thenReturn(naturalSeconds);
        PlaylistItem it = mock(PlaylistItem.class);
        when(it.getContentFile()).thenReturn(f);
        when(it.getPosition()).thenReturn(position);
        when(it.getDurationSeconds()).thenReturn(overrideSeconds);
        return it;
    }
}
