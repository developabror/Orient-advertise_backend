package uz.orientadvertise.services.domain.model;

import java.time.Duration;
import java.time.Instant;

public final class DeviceStatusEvaluator {

    public static final Duration OFFLINE_THRESHOLD = Duration.ofMinutes(15);
    public static final Duration CLOCK_SKEW_GRACE = Duration.ofSeconds(60);

    private DeviceStatusEvaluator() {
    }

    /**
     * Derive a device's effective status from its last heartbeat and content state.
     *
     * Rules:
     *   - lastHeartbeatAt is null OR (now - lastHeartbeatAt) > 15min + 60s grace → OFFLINE
     *   - hasContent == false → NO_CONTENT
     *   - otherwise → ONLINE
     *
     * The 60-second grace window absorbs minor clock skew between server and devices.
     */
    public static Device.Status evaluate(Instant lastHeartbeatAt, boolean hasContent, Instant now) {
        if (isOffline(lastHeartbeatAt, now)) {
            return Device.Status.OFFLINE;
        }

        if (!hasContent) {
            return Device.Status.NO_CONTENT;
        }

        return Device.Status.ONLINE;
    }

    /**
     * The OFFLINE rule on its own: no heartbeat, or one older than 15 min + 60 s grace. The same
     * cut-off {@code device_status_view} uses ({@code INTERVAL '16' MINUTE}), so every status
     * surface agrees on whether a device is showing as offline.
     */
    public static boolean isOffline(Instant lastHeartbeatAt, Instant now) {
        return lastHeartbeatAt == null
                || Duration.between(lastHeartbeatAt, now).compareTo(OFFLINE_THRESHOLD.plus(CLOCK_SKEW_GRACE)) > 0;
    }
}
