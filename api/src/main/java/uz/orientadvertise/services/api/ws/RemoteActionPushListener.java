package uz.orientadvertise.services.api.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.orientadvertise.services.domain.content.DevicePushChannel;
import uz.orientadvertise.services.domain.content.DevicePushFrames;
import uz.orientadvertise.services.service.RemoteActionIssuedEvent;

/**
 * Pushes {@code ACTION_PENDING} to a connected device the moment a remote action is committed
 * (VG-04). Until now the frame was only replayed when a device (re)connected, so an operator's
 * Reboot/Next/Prev reached a connected device at its next heartbeat — up to ~2 minutes — and two
 * missed beats let the 5-minute action expire unexecuted.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT}, so a rolled-back issue never reaches a device;
 * {@code fallbackExecution} because the group path saves each action in its own short transaction,
 * so the event can be published with no transaction open (the row is committed by then).
 *
 * <p>Best-effort: an offline device, or a failed write, is picked up by the heartbeat, the connect
 * replay or {@code GET /actions/pending}, exactly as before. The device deduplicates by
 * {@code actionId}, so receiving the same action twice is harmless.
 */
@Component
public class RemoteActionPushListener {

    private static final Logger log = LoggerFactory.getLogger(RemoteActionPushListener.class);

    private final DevicePushChannel pushChannel;

    public RemoteActionPushListener(DevicePushChannel pushChannel) {
        this.pushChannel = pushChannel;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRemoteActionIssued(RemoteActionIssuedEvent event) {
        try {
            boolean sent = pushChannel.push(event.deviceId(),
                    DevicePushFrames.actionPending(event.actionId(), event.actionType(), event.issuedAt()));
            log.debug("ACTION_PENDING [action={}, device={}, type={}] {}", event.actionId(), event.deviceId(),
                    event.actionType(), sent ? "pushed" : "not connected — heartbeat will deliver it");
        } catch (RuntimeException e) {
            // Never let a push failure surface to the caller: the action is committed and will still
            // be delivered by the heartbeat. (A concurrent send on the same socket can throw
            // IllegalStateException rather than IOException.)
            log.warn("ACTION_PENDING push failed [action={}, device={}]: {}",
                    event.actionId(), event.deviceId(), e.toString());
        }
    }
}
