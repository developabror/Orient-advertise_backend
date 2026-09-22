package uz.orientadvertise.services.domain.content;

import java.time.Instant;

/**
 * Server → device WebSocket frames that more than one component emits, formatted in one place so the
 * connect-time replay and the live push can never drift apart.
 */
public final class DevicePushFrames {

    private DevicePushFrames() {
    }

    /**
     * {@code ACTION_PENDING}: id, type and issue time only — the device fetches the payload with
     * {@code GET /api/devices/{id}/actions/pending} and deduplicates by {@code actionId}.
     */
    public static String actionPending(long actionId, String actionType, Instant issuedAt) {
        return """
                {"type":"%s","actionId":%d,"actionType":"%s","issuedAt":"%s"}"""
                .formatted(PushMessageType.ACTION_PENDING.name(), actionId, actionType, issuedAt);
    }
}
