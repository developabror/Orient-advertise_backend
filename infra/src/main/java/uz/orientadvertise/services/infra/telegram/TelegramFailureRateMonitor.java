package uz.orientadvertise.services.infra.telegram;

import java.time.Duration;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sliding-window watcher over the most recent Telegram send outcomes. When the failure
 * rate exceeds {@value #DEFAULT_THRESHOLD_PERCENT}% over the last
 * {@value #DEFAULT_WINDOW_MINUTES} minutes (with at least {@link #MIN_SAMPLES} samples),
 * a critical warning is emitted at {@code ERROR} level so it lands in the application
 * log file.
 *
 * <p><b>Loop-back avoidance.</b> The spec says "do not loop back to Telegram". This
 * class lives in {@code uz.orientadvertise.services.infra.telegram} — the package
 * that {@link TelegramAppender#RECURSIVE_LOGGER_PREFIXES} explicitly excludes from the
 * Telegram-forwarding pipeline. So an ERROR log emitted here goes to the file
 * appenders only, never to the bot. (Belt-and-braces: the appender additionally has a
 * thread-local {@code SENDING} guard that suppresses re-entry on the forwarder thread.)
 *
 * <p><b>Window bookkeeping.</b> Each outcome is appended as a {@code (timestamp,
 * success)} sample to a {@link ConcurrentLinkedDeque}. Reads (head eviction + counts)
 * walk the deque newest-first and stop at the cutoff — so the work is bounded by the
 * number of in-window events. At a worst-case ~30 sends/min × 5 min = 150 samples,
 * this is cheap enough to do on every recording call.
 *
 * <p><b>Throttling.</b> Once the threshold trips, the warning is throttled to once per
 * {@value #DEFAULT_WARN_THROTTLE_MINUTES} minutes — without that, a sustained outage
 * would fill the log with a near-identical line per send. The throttle is itself a
 * timestamp on an {@link AtomicLong} so two threads racing past the threshold can't
 * both emit.
 *
 * <p><b>Why this lives separately from {@link TelegramMetrics}:</b> the metrics class
 * publishes lifetime counters for dashboards. This monitor is operational (it logs)
 * and time-bounded (5 min). Mixing those concerns would muddle both. They share the
 * same call sites though — every {@code recordSuccess}/{@code recordFailure} on the
 * metrics also calls the equivalent on this monitor.
 */
public class TelegramFailureRateMonitor {

    private static final Logger log = LoggerFactory.getLogger(TelegramFailureRateMonitor.class);

    public static final int DEFAULT_WINDOW_MINUTES = 5;
    public static final int DEFAULT_THRESHOLD_PERCENT = 50;
    public static final int DEFAULT_WARN_THROTTLE_MINUTES = 5;
    /**
     * Don't fire on a tiny sample — a single 1-of-2 failure is 50% but not actionable.
     * Tuned so that a real outage (every send failing) trips within ~10 events, while
     * background noise can't.
     */
    public static final int MIN_SAMPLES = 10;

    private final Duration window;
    private final double thresholdRate;
    private final Duration warnThrottle;
    private final Deque<Sample> samples = new ConcurrentLinkedDeque<>();
    private final AtomicLong lastWarnEpochMs = new AtomicLong(0);

    public TelegramFailureRateMonitor() {
        this(Duration.ofMinutes(DEFAULT_WINDOW_MINUTES),
                DEFAULT_THRESHOLD_PERCENT / 100.0,
                Duration.ofMinutes(DEFAULT_WARN_THROTTLE_MINUTES));
    }

    /** Test ctor — lets a unit test slot in tighter windows / thresholds. */
    TelegramFailureRateMonitor(Duration window, double thresholdRate, Duration warnThrottle) {
        this.window = window;
        this.thresholdRate = thresholdRate;
        this.warnThrottle = warnThrottle;
    }

    public void recordSuccess() {
        record(true);
    }

    public void recordFailure() {
        record(false);
    }

    private void record(boolean success) {
        long now = System.currentTimeMillis();
        samples.offerLast(new Sample(now, success));
        evictOlderThanCutoff(now);
        checkAndWarn(now);
    }

    private void evictOlderThanCutoff(long now) {
        long cutoff = now - window.toMillis();
        // Single-direction eviction from the head — samples are inserted in time order,
        // so once we hit one inside the window, we're done.
        while (true) {
            Sample head = samples.peekFirst();
            if (head == null || head.timestamp() >= cutoff) break;
            samples.pollFirst();
        }
    }

    private void checkAndWarn(long now) {
        // Snapshot counts in one walk to avoid a torn read.
        int total = 0;
        int failed = 0;
        Iterator<Sample> it = samples.descendingIterator();
        while (it.hasNext()) {
            Sample s = it.next();
            total++;
            if (!s.success()) failed++;
        }
        if (total < MIN_SAMPLES) return;
        double rate = (double) failed / total;
        if (rate <= thresholdRate) return;

        long lastWarn = lastWarnEpochMs.get();
        if (now - lastWarn < warnThrottle.toMillis()) return;
        if (!lastWarnEpochMs.compareAndSet(lastWarn, now)) return;

        // The "critical" log lands at ERROR level so it is conspicuous in the file
        // appender. Lives in this package so the TelegramAppender's recursion guard
        // (RECURSIVE_LOGGER_PREFIXES) keeps it out of the Telegram-forwarding pipeline —
        // we MUST NOT alarm-on-Telegram-via-Telegram.
        log.error("CRITICAL: Telegram failure rate {}% over last {} minute(s) "
                        + "({} of {} samples failed). Threshold {}%. "
                        + "Investigate Telegram connectivity and bot authorization.",
                (int) (rate * 100.0),
                window.toMinutes(),
                failed, total,
                (int) (thresholdRate * 100.0));
    }

    /** Visible for tests / diagnostics. */
    public int sampleCount() {
        return samples.size();
    }

    /** Visible for tests / diagnostics. */
    public double currentFailureRate() {
        int total = 0;
        int failed = 0;
        for (Sample s : samples) {
            total++;
            if (!s.success()) failed++;
        }
        return total == 0 ? 0.0 : (double) failed / total;
    }

    private record Sample(long timestamp, boolean success) {}
}
