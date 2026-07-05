package uz.orientadvertise.services.service;

/**
 * Published when {@link PlaylistItemService} successfully mutates a playlist
 * (add / remove / move / reorder / set-duration). Consumed by the WebSocket-side
 * listener that pushes an instant {@code SYNC_CONTENT} notification to devices
 * currently bound to that playlist via an active CONFIRMED assignment.
 *
 * <p>Mirrors {@link AssignmentConfirmedEvent}. Listeners must subscribe with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so a rolled-back
 * mutation (validation throw, FK violation, etc.) never produces a push.
 * Offline / WS-disconnected devices in the affected target scopes still pick
 * up the change on their next heartbeat poll via
 * {@code DeviceSyncService.computeSyncPlan} — the offline fallback path that
 * resolves status-free.
 *
 * <p>The event carries only {@code playlistId} on purpose: the affected device
 * set is derived at listener time against <em>current</em> active assignments,
 * so a confirm that lands between publish and listener fire is reflected.
 */
public record PlaylistReorderedEvent(Long playlistId) {
}
