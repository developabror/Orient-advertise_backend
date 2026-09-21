package uz.orientadvertise.services.service;

import java.util.List;

/**
 * Published when {@link ContentAssignmentService} stops an assignment from driving a set of
 * devices. Two producers, and the row's fate differs between them — the event says
 * "these devices must re-resolve NOW", nothing more:
 *
 * <ul>
 *   <li>{@code softDelete} — an operator cancelled a <em>CONFIRMED</em> assignment; the row is
 *       soft-deleted and {@code deviceIds} is its whole (uncancelled) target.</li>
 *   <li>{@code supersede} — a REPLACE confirm handed {@code deviceIds} over to a new assignment.
 *       The predecessor may be soft-deleted, truncated, narrowed by exclusions, or (since
 *       v1.0.142, when the new window ends before its own) <b>completely unchanged</b> and simply
 *       outranked for the new window — in that last case it will drive these devices again as
 *       soon as the new assignment's window closes.</li>
 * </ul>
 *
 * <p>Mirror of {@link AssignmentConfirmedEvent}: listeners must subscribe with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so a rolled-back change never
 * produces a push. On re-resolution a device picks up the winning active assignment under
 * {@code ContentAssignment.PRECEDENCE}, or — if none remains — is told to purge its held content.
 * Cancelling a DRAFT publishes nothing (a draft never drove any device); offline /
 * WS-disconnected devices reconcile on their next heartbeat via
 * {@code DeviceSyncService.computeSyncPlan}.
 */
public record AssignmentCancelledEvent(Long assignmentId, List<Long> deviceIds) {
}
