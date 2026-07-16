package uz.orientadvertise.services.api.dto;

import uz.orientadvertise.services.service.SyncGroupPlaybackService.PlaybackItemView;

/**
 * One pickable item on a sync group's shared playback timeline (for the FE jump picker).
 * {@code index} is the 0-based deliverable ordinal the operator jumps to; {@code slotStartMs} is the
 * loop-relative start offset the re-anchor is computed from.
 */
public record SyncGroupPlaybackItem(int index, Long fileId, String title, Integer durationSeconds,
                                    long slotStartMs, long slotDurationMs) {

    static SyncGroupPlaybackItem from(PlaybackItemView v) {
        return new SyncGroupPlaybackItem(v.index(), v.fileId(), v.title(), v.durationSeconds(),
                v.slotStartMs(), v.slotDurationMs());
    }
}
