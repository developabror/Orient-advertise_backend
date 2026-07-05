package uz.orientadvertise.services.api.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.orientadvertise.services.domain.content.SyncDispatcher;
import uz.orientadvertise.services.service.AssignmentCancelledEvent;

/**
 * Pushes a {@code SYNC_CONTENT} notification to the formerly-targeted devices the instant a
 * CONFIRMED assignment is cancelled (soft-deleted). Sibling of
 * {@link AssignmentConfirmedSyncPushListener}.
 *
 * <p>Subscribes with {@link TransactionPhase#AFTER_COMMIT} so a rolled-back cancel never
 * produces a push. On re-resolution the device picks up the next-priority active assignment
 * or is told to purge its held content. The dispatcher silently skips devices that are not
 * WS-connected — they reconcile on their next heartbeat poll via
 * {@code DeviceSyncService.computeSyncPlan} (the offline fallback path that resolves
 * status-free).
 */
@Component
public class AssignmentCancelledSyncPushListener {

    private static final Logger log = LoggerFactory.getLogger(AssignmentCancelledSyncPushListener.class);
    private static final String REASON = "assignment-cancelled";

    private final SyncDispatcher syncDispatcher;

    public AssignmentCancelledSyncPushListener(SyncDispatcher syncDispatcher) {
        this.syncDispatcher = syncDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssignmentCancelled(AssignmentCancelledEvent event) {
        if (event.deviceIds() == null || event.deviceIds().isEmpty()) {
            log.debug("Assignment {} cancelled with empty device id set — no push", event.assignmentId());
            return;
        }
        log.info("Pushing sync to {} device(s) for cancelled assignment {}",
                event.deviceIds().size(), event.assignmentId());
        syncDispatcher.dispatchSyncToDevices(event.deviceIds(), REASON);
    }
}
