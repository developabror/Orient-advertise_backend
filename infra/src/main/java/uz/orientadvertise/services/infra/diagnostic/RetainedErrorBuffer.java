package uz.orientadvertise.services.infra.diagnostic;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Static, bounded ring buffer of recent WARN/ERROR log events.
 *
 * <p><b>Why a separate buffer?</b> The {@link uz.orientadvertise.services.infra.telegram.TelegramAppender}
 * forwards events to Telegram by <em>draining</em> its queue — once delivered, the events
 * are gone. Diagnostics ({@code /health}'s "last 5 errors") need <em>retention</em>
 * instead: the same events stay readable in-memory for a sliding window so an operator
 * can ask the bot "what went wrong recently?" without going to the log files. The
 * appender pushes to both buffers in parallel, so this buffer never affects telegram
 * delivery and vice-versa.
 *
 * <p><b>Bounded capacity.</b> The newest {@value #CAPACITY} events are kept; older ones
 * are evicted as new entries arrive. {@link #recent(Duration, int)} additionally filters
 * by age — events older than the requested window are excluded even if they're still in
 * the ring. Dual bound: capacity caps memory; window caps relevance.
 *
 * <p><b>Thread safety.</b> Single intrinsic lock around the deque. Writes happen on the
 * Logback append thread (frequency dominated by error rate, not request rate), reads
 * happen on the bot's command thread (rare — only when an operator types {@code /health}).
 * A lock-free design wasn't worth the complexity at this volume.
 *
 * <p><b>"Never crashes the logger."</b> All {@link #append} paths swallow throwables —
 * a bug here must not propagate to the appender and poison the application's primary
 * logging path. Same posture as {@link uz.orientadvertise.services.infra.telegram.TelegramAppender}.
 */
public final class RetainedErrorBuffer {

    /** Hard memory cap. 100 entries × ~1 KB each ≈ 100 KB. */
    public static final int CAPACITY = 100;

    private static final Object LOCK = new Object();
    private static final Deque<RetainedError> BUFFER = new ArrayDeque<>(CAPACITY);

    private RetainedErrorBuffer() {}

    /**
     * Append an event. Newest first; oldest evicted when {@link #CAPACITY} is exceeded.
     * Never throws — a misbehaving caller must not corrupt the appender.
     */
    public static void append(Instant timestamp, String level, String logger,
                                String message, String exception) {
        try {
            var entry = new RetainedError(
                    timestamp == null ? Instant.now() : timestamp,
                    level == null ? "ERROR" : level,
                    logger == null ? "?" : logger,
                    message == null ? "" : message,
                    exception);
            synchronized (LOCK) {
                while (BUFFER.size() >= CAPACITY) BUFFER.pollFirst();
                BUFFER.offerLast(entry);
            }
        } catch (Throwable ignored) {
            // Belt-and-braces: a bug in this buffer must NEVER kill the logger thread.
        }
    }

    /**
     * Return up to {@code limit} most-recent events at the given log {@code level}.
     * Newest first. No time-window — the buffer is bounded by capacity, so the oldest
     * entry returned is whatever happens to still be in the ring. Used by {@code /logs}
     * to filter the WARN+ERROR buffer down to ERROR-only without iterating the whole
     * deque twice. Empty list when nothing matches.
     */
    public static List<RetainedError> recentByLevel(String level, int limit) {
        if (level == null || level.isBlank() || limit <= 0) return List.of();
        var snapshot = new ArrayList<RetainedError>();
        synchronized (LOCK) {
            var it = BUFFER.descendingIterator();
            while (it.hasNext() && snapshot.size() < limit) {
                var e = it.next();
                if (level.equalsIgnoreCase(e.level())) snapshot.add(e);
            }
        }
        return List.copyOf(snapshot);
    }

    /**
     * Return up to {@code limit} most-recent events whose timestamp falls within
     * {@code window} of now. Newest first. Empty list when nothing matches — a quiet
     * service is the success case.
     */
    public static List<RetainedError> recent(Duration window, int limit) {
        if (window == null || limit <= 0) return List.of();
        Instant cutoff = Instant.now().minus(window);
        var snapshot = new ArrayList<RetainedError>();
        synchronized (LOCK) {
            // Iterate newest → oldest (descending). The deque was inserted with offerLast,
            // so descendingIterator gives newest first.
            var it = BUFFER.descendingIterator();
            while (it.hasNext() && snapshot.size() < limit) {
                var e = it.next();
                if (e.timestamp().isBefore(cutoff)) break;
                snapshot.add(e);
            }
        }
        return List.copyOf(snapshot);
    }

    /** Visible for tests / diagnostics. */
    public static int size() {
        synchronized (LOCK) {
            return BUFFER.size();
        }
    }

    /** Visible for tests so cases aren't order-dependent. */
    public static void resetForTests() {
        synchronized (LOCK) {
            BUFFER.clear();
        }
    }

    /**
     * One retained event. {@code message} is the formatted log message, {@code exception}
     * is the throwable's class + message (no stack — those bloat the buffer and aren't
     * shown in the {@code /health} summary; if an operator wants the full stack they go
     * to the log files).
     */
    public record RetainedError(
            Instant timestamp,
            String level,
            String logger,
            String message,
            String exception   // nullable
    ) {}
}
