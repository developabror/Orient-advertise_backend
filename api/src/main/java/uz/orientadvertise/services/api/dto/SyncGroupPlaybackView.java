package uz.orientadvertise.services.api.dto;

import java.time.Instant;
import java.util.List;

import uz.orientadvertise.services.service.SyncGroupPlaybackService.PlaybackView;

/**
 * Response for {@code GET /api/sync-groups/{id}/playback}: the pickable item list the operator can jump
 * the whole group to, plus the currently-active jump (if any).
 *
 * <p>When {@code coherent} is false (members resolve different content, some member has no active
 * playlist, or the group is empty) {@code reason} explains why and {@code items} is empty — the FE
 * disables the picker and shows the reason.
 */
public record SyncGroupPlaybackView(
        Long syncGroupId,
        boolean coherent,
        String reason,
        Long playlistId,
        String playlistName,
        long loopDurationMs,
        int memberCount,
        List<SyncGroupPlaybackItem> items,
        ActiveJump activeJump) {

    /** The override currently steering the group, if one still matches the resolved version. */
    public record ActiveJump(int index, long activateAt, String activateAtIso) {}

    public static SyncGroupPlaybackView from(PlaybackView v) {
        var items = v.items().stream().map(SyncGroupPlaybackItem::from).toList();
        ActiveJump activeJump = v.activeJump() == null ? null
                : new ActiveJump(v.activeJump().index(), v.activeJump().activateAtEpochMs(),
                        Instant.ofEpochMilli(v.activeJump().activateAtEpochMs()).toString());
        return new SyncGroupPlaybackView(v.syncGroupId(), v.coherent(), v.reason(),
                v.playlistId(), v.playlistName(), v.loopDurationMs(), v.memberCount(), items, activeJump);
    }
}
