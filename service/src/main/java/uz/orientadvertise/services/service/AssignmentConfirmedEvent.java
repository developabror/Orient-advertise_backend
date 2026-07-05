package uz.orientadvertise.services.service;

import java.util.List;

/**
 * Published when {@link ContentAssignmentService} successfully flips a draft to
 * CONFIRMED. Consumed by the WebSocket-side listener that pushes an instant SYNC
 * notification to currently-connected devices in the assignment's target scope.
 *
 * <p>Listeners should subscribe with {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} so a rolled-back confirm (e.g. the 409 overlap re-check at
 * confirmation time) never produces a push. Offline / WS-disconnected devices
 * in {@code deviceIds} are still skipped by the dispatcher and pick up the new
 * content on their next heartbeat poll — that fallback path is owned by
 * {@code DeviceSyncService.computeSyncPlan}.
 */
public record AssignmentConfirmedEvent(Long assignmentId, List<Long> deviceIds) {
}
