package uz.orientadvertise.services.infra.storage;

import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * Records whether the object-storage lifecycle rule is actually installed.
 *
 * <p>Exists because the previous failure mode was invisible past a single boot WARN. The old
 * abort-only rule failed MinIO's ILM validation on <em>every</em> start
 * ({@code MalformedXML}), the WARN scrolled past, and operators went on believing
 * application-managed cleanup was running when the stored lifecycle config was zero bytes. On a
 * volume that was 96% full, "cleanup silently does nothing" is not a debug-level fact.
 *
 * <p>Mirrors {@link MinioHealthStatus}: a tiny thread-safe holder in infra, read by a service-layer
 * indicator that publishes it on {@code GET /api/health}. Starts {@link State#PENDING} so a boot
 * that has not reached {@code ApplicationReadyEvent} is not reported as a failure.
 */
@Component
public class StorageLifecycleStatus {

    public enum State {
        /** Not attempted yet (pre-ApplicationReadyEvent), or MinIO was degraded when we tried. */
        PENDING,
        /** The rule is installed — or deliberately disabled by configuration. */
        OK,
        /** The install was attempted and rejected. Automated cleanup is NOT running. */
        FAILED
    }

    private record Snapshot(State state, String detail) {}

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(State.PENDING, "not attempted yet"));

    public void markInstalled(String detail) {
        current.set(new Snapshot(State.OK, detail));
    }

    /** Configuration turned expiry off — a deliberate choice, not a failure. */
    public void markDisabled(String detail) {
        current.set(new Snapshot(State.OK, detail));
    }

    public void markPending(String detail) {
        current.set(new Snapshot(State.PENDING, detail));
    }

    public void markFailed(String detail) {
        current.set(new Snapshot(State.FAILED, detail));
    }

    public State getState() {
        return current.get().state();
    }

    /** Human-readable reason for the current state; safe to surface on the public health endpoint. */
    public String getDetail() {
        return current.get().detail();
    }
}
