package uz.orientadvertise.services.api.dto;

import java.time.Instant;

import uz.orientadvertise.services.service.SyncGroupPlaybackService.JumpResultView;

/**
 * Response for {@code POST /api/sync-groups/{id}/playback/jump}: the coordinated cut-over the group was
 * re-anchored to. {@code anchorEpochMs = activateAtEpochMs − slotStart[index]}; every member's next
 * {@code /sync} returns this anchor and they converge on {@code index} at {@code activateAt}.
 *
 * <p>No fan-out counts: since VG-18 the push is dispatched only after the jump's transaction
 * commits, so nothing has been sent when this response is built. Pushing before the commit is
 * exactly what made a member miss the jump it was being told about. Offline members converge on
 * their next heartbeat either way.
 */
public record SyncGroupJumpResult(
        Long syncGroupId,
        int index,
        long anchorEpochMs,
        long activateAtEpochMs,
        String activateAtIso,
        int memberCount) {

    public static SyncGroupJumpResult from(JumpResultView v) {
        return new SyncGroupJumpResult(v.syncGroupId(), v.index(), v.anchorEpochMs(),
                v.activateAtEpochMs(), Instant.ofEpochMilli(v.activateAtEpochMs()).toString(),
                v.memberCount());
    }
}
