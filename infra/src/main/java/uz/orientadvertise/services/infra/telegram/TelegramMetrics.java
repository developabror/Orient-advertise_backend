package uz.orientadvertise.services.infra.telegram;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Metrics for Telegram API outcomes, exposed under {@code telegram.*} on
 * {@code /actuator/metrics}.
 *
 * <p><b>Meters published:</b>
 * <ul>
 *   <li>{@code telegram.sent} (counter) — successful sends, lifetime.</li>
 *   <li>{@code telegram.failed} (counter) — failed sends, lifetime.</li>
 *   <li>{@code telegram.failed} tagged by {@code reason} ∈ {@code FORBIDDEN_403,
 *       TIMEOUT, NETWORK, OTHER} — failure breakdown.</li>
 *   <li>{@code telegram.failure_rate} (gauge, 0..1) — lifetime failure rate
 *       {@code failed/(sent+failed)}, or {@code 0} when no traffic yet. The 5-minute
 *       sliding-window rate that drives the alarm lives in
 *       {@link TelegramFailureRateMonitor}; this gauge is the long-term view.</li>
 *   <li>{@code telegram.last_failure_timestamp} (gauge) — epoch millis of the most
 *       recent failure, or {@code 0} if none yet. Pair with the {@link #lastFailureReason}
 *       accessor for the textual reason (Micrometer can't carry strings — exposed via a
 *       dedicated info accessor instead).</li>
 * </ul>
 *
 * <p><b>Why a separate {@link TelegramFailureRateMonitor}?</b> {@code failure_rate}
 * here is a lifetime average — useful for dashboards, useless for alarms (a healthy bot
 * with 100k successes drowns out a current outage). The 5-minute window monitor is a
 * separate component because its behaviour is operational (logs a critical warning),
 * not metric-shaped, and its retention horizon is different (5 min vs forever).
 */
public class TelegramMetrics {

    private static final Logger log = LoggerFactory.getLogger(TelegramMetrics.class);

    /** Failure category — drives the {@code reason} tag and the {@link #lastFailureReason}. */
    public enum FailureReason {
        /** Telegram returned 403 (kicked, blocked, removed). Triggers chat disabling. */
        FORBIDDEN_403,
        /** Send timeout — caller's per-send budget elapsed before reply. */
        TIMEOUT,
        /** Network-layer error — DNS, TCP, TLS, etc. */
        NETWORK,
        /** Anything else — schema validation, library bugs, generic 5xx, etc. */
        OTHER
    }

    private final MeterRegistry registry;
    private final Counter sentCounter;
    private final Counter failedCounter;
    private final AtomicLong lastFailureEpochMs = new AtomicLong(0);
    private final AtomicReference<String> lastFailureReason = new AtomicReference<>("none");

    public TelegramMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.sentCounter = Counter.builder("telegram.sent")
                .description("Successful Telegram API sends (lifetime)")
                .register(registry);
        this.failedCounter = Counter.builder("telegram.failed")
                .description("Failed Telegram API sends (lifetime)")
                .register(registry);
        Gauge.builder("telegram.failure_rate", this, TelegramMetrics::lifetimeFailureRate)
                .description("Lifetime failure rate (failed / total), 0..1")
                .register(registry);
        Gauge.builder("telegram.last_failure_timestamp", lastFailureEpochMs, AtomicLong::get)
                .description("Epoch milliseconds of most recent send failure, 0 if none")
                .register(registry);
    }

    /** Record a successful Telegram send. Hot path — keep cheap. */
    public void recordSuccess() {
        sentCounter.increment();
    }

    /**
     * Record a failed Telegram send. Increments both the unspecified {@code telegram.failed}
     * counter and the per-reason tagged counter (so dashboards can break down by category).
     * Updates the "last failure" timestamp and reason for at-a-glance triage.
     */
    public void recordFailure(FailureReason reason, String detail) {
        failedCounter.increment();
        // Per-reason counter — registered lazily because Micrometer dedupes by full
        // (name, tags) key, so the registry caches them automatically.
        Counter.builder("telegram.failed.by_reason")
                .description("Failed Telegram sends, broken down by failure category")
                .tag("reason", reason.name())
                .register(registry)
                .increment();
        lastFailureEpochMs.set(Instant.now().toEpochMilli());
        String safeDetail = detail == null || detail.isBlank() ? reason.name() : detail;
        // Keep "reason" terse — operators read this in a one-line summary.
        if (safeDetail.length() > 200) safeDetail = safeDetail.substring(0, 200) + "…";
        lastFailureReason.set(reason.name() + ": " + safeDetail);
    }

    /** Lifetime failure rate. Used by the {@code telegram.failure_rate} gauge. */
    public double lifetimeFailureRate() {
        double sent = sentCounter.count();
        double failed = failedCounter.count();
        double total = sent + failed;
        return total == 0 ? 0.0 : failed / total;
    }

    /** Most recent failure reason as a human-readable string. */
    public String lastFailureReason() {
        return lastFailureReason.get();
    }

    /** Epoch millis of the most recent failure, or 0 if none. */
    public long lastFailureEpochMillis() {
        return lastFailureEpochMs.get();
    }

    /** Visible for tests. */
    public double sentTotal() { return sentCounter.count(); }
    /** Visible for tests. */
    public double failedTotal() { return failedCounter.count(); }
}
