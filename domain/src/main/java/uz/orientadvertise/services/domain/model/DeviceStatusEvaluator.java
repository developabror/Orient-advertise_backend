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
        if (lastHeartbeatAt == null) {
            return Device.Status.OFFLINE;
        }

        var age = Duration.between(lastHeartbeatAt, now);
        var thresholdWithGrace = OFFLINE_THRESHOLD.plus(CLOCK_SKEW_GRACE);

        if (age.compareTo(thresholdWithGrace) > 0) {
            return Device.Status.OFFLINE;
        }

        if (!hasContent) {
            return Device.Status.NO_CONTENT;
        }

        return Device.Status.ONLINE;
    }
}
