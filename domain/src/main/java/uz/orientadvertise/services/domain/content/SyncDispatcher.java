package uz.orientadvertise.services.domain.content;

import java.util.Collection;

public interface SyncDispatcher {

    /**
     * Send sync notifications to many devices, batched and throttled so a 1000-device
     * fan-out does not flood the WebSocket layer in a single tick.
     *
     * Implementations should:
     *   - chunk the device list into manageable batches
     *   - stagger batch dispatches over time (e.g. one batch per 100 ms)
     *   - return {@link DispatchResult} summarising sent / queued / skipped counts
     *
     * Devices not currently connected to a WebSocket are recorded as 'skipped' —
     * they pick up the new content on their next heartbeat poll, where the
     * version mismatch triggers a SYNC_CONTENT pending action.
     */
    DispatchResult dispatchSyncToDevices(Collection<Long> deviceIds, String reason);

    record DispatchResult(int totalDevices, int batches, int sent, int skipped, int failed) {}
}
