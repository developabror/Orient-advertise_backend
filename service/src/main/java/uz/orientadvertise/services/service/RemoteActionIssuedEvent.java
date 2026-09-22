package uz.orientadvertise.services.service;

import java.time.Instant;

/**
 * Published whenever a {@code remote_action} row is created (single-device, playlist control, group
 * bulk). Consumed by the WebSocket-side listener that pushes {@code ACTION_PENDING} to the device the
 * moment the action is committed (VG-04) — before this, a connected device only learned of an action
 * at its next heartbeat (~2 min), and two missed beats let the 5-minute action expire unexecuted.
 *
 * <p>Carries plain values, not the entity: the listener runs after commit, outside any persistence
 * context.
 */
public record RemoteActionIssuedEvent(Long actionId, Long deviceId, String actionType, Instant issuedAt) {
}
