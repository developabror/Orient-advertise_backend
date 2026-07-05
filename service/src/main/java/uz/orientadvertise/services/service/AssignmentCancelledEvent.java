package uz.orientadvertise.services.service;

import java.util.List;

/**
 * Published when {@link ContentAssignmentService} cancels (soft-deletes) a
 * <em>CONFIRMED</em> assignment. Consumed by the WebSocket-side listener that pushes an
 * instant SYNC notification to the formerly-targeted devices so they re-resolve their
 * content within ~1s instead of waiting up to one heartbeat poll.
 *
 * <p>Mirror of {@link AssignmentConfirmedEvent}: listeners must subscribe with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so a rolled-back cancel never
 * produces a push. On re-resolution a device picks up the next-priority active assignment,
 * or — if none remains — is told to purge its held content. Cancelling a DRAFT publishes
 * nothing (a draft never drove any device); offline / WS-disconnected devices reconcile on
 * their next heartbeat via {@code DeviceSyncService.computeSyncPlan}.
 */
public record AssignmentCancelledEvent(Long assignmentId, List<Long> deviceIds) {
}
