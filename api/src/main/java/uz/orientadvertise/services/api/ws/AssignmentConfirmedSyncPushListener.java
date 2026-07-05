package uz.orientadvertise.services.api.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.orientadvertise.services.domain.content.SyncDispatcher;
import uz.orientadvertise.services.service.AssignmentConfirmedEvent;

/**
 * Pushes a {@code SYNC_CONTENT} notification to currently-connected devices
 * the instant an assignment is confirmed.
 *
 * <p>Subscribes with {@link TransactionPhase#AFTER_COMMIT} so a rolled-back
 * confirm (e.g. the 409 overlap re-check inside
 * {@link uz.orientadvertise.services.service.ContentAssignmentService#confirmWithExclusions})
 * never produces a push. The dispatcher itself silently skips devices that are
 * not WS-connected — they pick up the new content on their next heartbeat poll,
 * which goes through {@code DeviceSyncService.computeSyncPlan} (the offline
 * fallback path that resolves status-free).
 *
 * <p>This is the single production caller of
 * {@link SyncDispatcher#dispatchSyncToDevices}. Tests reference it via
 * {@code BatchedSyncDispatcherTest} for the batching contract; this listener
 * just connects domain events to that contract.
 */
@Component
public class AssignmentConfirmedSyncPushListener {

    private static final Logger log = LoggerFactory.getLogger(AssignmentConfirmedSyncPushListener.class);
    private static final String REASON = "assignment-confirmed";

    private final SyncDispatcher syncDispatcher;

    public AssignmentConfirmedSyncPushListener(SyncDispatcher syncDispatcher) {
        this.syncDispatcher = syncDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssignmentConfirmed(AssignmentConfirmedEvent event) {
        if (event.deviceIds() == null || event.deviceIds().isEmpty()) {
            log.debug("Assignment {} confirmed with empty device id set — no push", event.assignmentId());
            return;
        }
        log.info("Pushing sync to {} device(s) for confirmed assignment {}",
                event.deviceIds().size(), event.assignmentId());
        syncDispatcher.dispatchSyncToDevices(event.deviceIds(), REASON);
    }
}
