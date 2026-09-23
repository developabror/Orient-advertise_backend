package uz.orientadvertise.services.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.orientadvertise.services.domain.content.SyncDispatcher;

/**
 * Tells a sync group's members to re-sync, once the jump that moved them is committed (VG-18).
 *
 * <p>{@code SyncGroupPlaybackService.jumpToIndex} used to dispatch this itself, from inside its own
 * transaction. A member that took the push immediately re-read the group override in a separate
 * transaction, could not see the not-yet-committed row, and resolved the base anchor instead — so it
 * kept playing the old position while the rest of its sales point jumped. It recovered only on its
 * next {@code /sync}, which for a device in schedule mode is up to ten minutes.
 *
 * <p>Sibling of {@code RemoteActionPushListener} and {@code SyncGroupOverrideCleaner}, and the same
 * rule as both: the push is an accelerator, the heartbeat and {@code /sync} are the contract, so a
 * failed push costs latency and never correctness.
 */
@Component
public class SyncGroupJumpPushListener {

    private static final Logger log = LoggerFactory.getLogger(SyncGroupJumpPushListener.class);
    private static final String REASON = "sync-group-jump";

    private final SyncDispatcher syncDispatcher;

    public SyncGroupJumpPushListener(SyncDispatcher syncDispatcher) {
        this.syncDispatcher = syncDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSyncGroupJumped(SyncGroupJumpedEvent event) {
        if (event.memberIds() == null || event.memberIds().isEmpty()) {
            return;
        }
        var dispatch = syncDispatcher.dispatchSyncToDevices(event.memberIds(), REASON);
        log.info("Pushed sync to {} member(s) of sync group {} after a jump [sent={}, skipped={}, failed={}]",
                event.memberIds().size(), event.syncGroupId(),
                dispatch.sent(), dispatch.skipped(), dispatch.failed());
    }
}
