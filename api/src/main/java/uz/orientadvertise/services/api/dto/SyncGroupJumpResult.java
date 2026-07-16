package uz.orientadvertise.services.api.dto;

import java.time.Instant;

import uz.orientadvertise.services.service.SyncGroupPlaybackService.JumpResultView;

/**
 * Response for {@code POST /api/sync-groups/{id}/playback/jump}: the coordinated cut-over the group was
 * re-anchored to. {@code anchorEpochMs = activateAtEpochMs − slotStart[index]}; every member's next
 * {@code /sync} returns this anchor and they converge on {@code index} at {@code activateAt}.
 *
 * <p>{@code dispatched} reflects the immediate push fan-out; counts are best-effort (the batched
 * dispatcher returns before its staggered batches fire, so offline members converge on their next
 * heartbeat regardless).
 */
public record SyncGroupJumpResult(
        Long syncGroupId,
        int index,
        long anchorEpochMs,
        long activateAtEpochMs,
        String activateAtIso,
        int memberCount,
        Dispatched dispatched) {

    public record Dispatched(int sent, int skipped, int failed) {}

    public static SyncGroupJumpResult from(JumpResultView v) {
        return new SyncGroupJumpResult(v.syncGroupId(), v.index(), v.anchorEpochMs(),
                v.activateAtEpochMs(), Instant.ofEpochMilli(v.activateAtEpochMs()).toString(),
                v.memberCount(), new Dispatched(v.sent(), v.skipped(), v.failed()));
    }
}
