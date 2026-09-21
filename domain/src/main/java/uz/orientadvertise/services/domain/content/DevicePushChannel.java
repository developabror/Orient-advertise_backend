package uz.orientadvertise.services.domain.content;

/**
 * Single-device server → device push seam, implemented by the WebSocket handler in the
 * {@code api} module and consumed from {@code service}. Mirrors the existing
 * {@link SyncDispatcher} port: the service layer must never import {@code api} (ArchUnit
 * {@code LayerDependencyTest} fails the build if it does), so the socket map is reached
 * through this interface.
 *
 * <p>Deliberately narrow — one device, one already-serialised JSON frame. Fan-out and
 * batching stay with {@link SyncDispatcher}; this is for targeted control messages where
 * the caller also needs to know whether the device was actually reachable.
 */
public interface DevicePushChannel {

    /**
     * Whether the device currently holds an open socket. This is live truth, not the
     * heartbeat-derived {@code device_status_view} status (which lags up to 16 minutes and
     * is useless for a Connect button).
     */
    boolean isConnected(Long deviceId);

    /**
     * Best-effort push of one pre-rendered JSON frame. Returns {@code true} only when the
     * frame was actually written to an open socket; {@code false} means the device is
     * offline or the write failed. A {@code false} is <b>not</b> an error — the caller is
     * expected to have a heartbeat fallback, exactly as content sync does.
     */
    boolean push(Long deviceId, String message);
}
