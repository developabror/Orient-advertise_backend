package uz.orientadvertise.services.infra.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records whether MinIO is currently reachable, why it stopped being reachable, and since when.
 *
 * <p>Until v1.0.144 this was a bare two-state flag with one writer that could ever set it back to
 * {@link State#UP} — {@link MinioBucketInitializer}, which runs once at startup. Anything that
 * failed a MinIO call flipped it to {@link State#DEGRADED} and <b>nothing</b> flipped it back, so a
 * single disk-full moment, a MinIO restart or a network blip disabled every storage endpoint until
 * an operator restarted the application. A health flag with no re-probe is not a flag, it is a
 * latch. {@link MinioHealthProbe} is the re-probe; this class is the state it heals.
 *
 * <p>Modelled on {@link StorageLifecycleStatus}: one immutable snapshot behind an
 * {@link AtomicReference}, so the state and the reason that goes with it can never be read torn.
 *
 * <p><b>Logging is per TRANSITION, not per call.</b> Every failed storage call reaches
 * {@link #markDegraded(String)} and every successful probe reaches {@link #markUp()}; a fleet of
 * TV boxes syncing every minute would turn a per-call WARN into a Telegram flood (WARN and above
 * is forwarded to the operator chat). Re-marking a state we are already in therefore logs nothing
 * and — just as important — does NOT move {@link #getSince()}, so "how long has storage been
 * down" stays the answer to the question an operator is actually asking.
 */
@Component
public class MinioHealthStatus {

    private static final Logger log = LoggerFactory.getLogger(MinioHealthStatus.class);

    public enum State { UP, DEGRADED }

    /** Reason carried before anything has probed MinIO at all (pre-{@code ApplicationRunner}). */
    static final String NOT_PROBED = "not probed yet (startup)";

    private record Snapshot(State state, String reason, Instant since) {}

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(State.DEGRADED, NOT_PROBED, Instant.now()));

    /**
     * MinIO answered — clear the latch. Logs INFO once, on the UP transition only, including how
     * long storage was degraded and what took it down.
     */
    public void markUp() {
        Instant now = Instant.now();
        // getAndUpdate is a CAS loop: exactly one caller observes a non-UP previous snapshot, so
        // exactly one caller logs the recovery even under concurrent probes.
        Snapshot previous = current.getAndUpdate(
                s -> s.state() == State.UP ? s : new Snapshot(State.UP, "available", now));
        if (previous.state() != State.UP) {
            log.info("MinIO storage is available again — UP after {} degraded ({})",
                    humanize(Duration.between(previous.since(), now)), previous.reason());
        }
    }

    /**
     * MinIO could not be reached. Logs WARN once, on the DEGRADED transition only.
     *
     * @param reason short, operator-readable and free of secrets — it is published verbatim on the
     *               unauthenticated {@code GET /api/health}. Callers pass an operation plus an
     *               exception <em>type</em> (e.g. {@code "upload: ConnectException"}), never a raw
     *               exception message, which can carry endpoints and credentials.
     */
    public void markDegraded(String reason) {
        String detail = reason == null || reason.isBlank() ? "unspecified storage failure" : reason;
        Instant now = Instant.now();
        Snapshot previous = current.getAndUpdate(
                s -> s.state() == State.DEGRADED ? s : new Snapshot(State.DEGRADED, detail, now));
        if (previous.state() != State.DEGRADED) {
            log.warn("MinIO storage is DEGRADED — {}. Storage endpoints return 503 until a probe "
                    + "succeeds (app.minio.recheck-interval)", detail);
        }
    }

    public boolean isAvailable() {
        return current.get().state() == State.UP;
    }

    public State getState() {
        return current.get().state();
    }

    /** Why the current state was entered; safe to surface on the public health endpoint. */
    public String getReason() {
        return current.get().reason();
    }

    /** When the current state was ENTERED — never moved by a re-mark of the same state. */
    public Instant getSince() {
        return current.get().since();
    }

    /** Compact duration for log lines: {@code 42s}, {@code 3m12s}, {@code 2h07m}. */
    static String humanize(Duration d) {
        long seconds = Math.max(0, d.getSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return "%dm%02ds".formatted(seconds / 60, seconds % 60);
        }
        return "%dh%02dm".formatted(seconds / 3600, (seconds % 3600) / 60);
    }
}
