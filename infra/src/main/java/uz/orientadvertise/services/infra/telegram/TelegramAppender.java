package uz.orientadvertise.services.infra.telegram;

import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import ch.qos.logback.core.AppenderBase;
import uz.orientadvertise.services.infra.diagnostic.RetainedErrorBuffer;

/**
 * Logback appender that captures WARN and ERROR log events into a static, bounded
 * in-memory queue. {@link TelegramLogForwarder} (a Spring bean) drains the queue once
 * the bot is registered and ships the formatted entries to authorized chats.
 *
 * <p>The queue lives on a {@code static} field so events emitted before the Spring
 * context is up are buffered too — Logback initializes well before Spring beans wire,
 * and we explicitly want bootstrap warnings (DB connect failures, missing config, etc.)
 * to reach Telegram once the bot is alive.
 *
 * <p><b>Recursion guard.</b> The appender skips events from any logger under the
 * Telegram client's own packages or this telegram subsystem — otherwise an HTTP error
 * inside the bot would log → enqueue → drain → bot send → log → ... ad infinitum.
 * A {@link ThreadLocal} also short-circuits any re-entry on the forwarder's thread,
 * catching cases the package filter would miss (e.g. OkHttp log statements from inside
 * a {@code bot.send} call).
 *
 * <p><b>"Never crash the logger."</b> Every {@code append} path is wrapped: format
 * failures, queue overflow, ThreadLocal weirdness — all swallowed at the appender. The
 * static {@link #droppedCount()} counter exposes overflow for diagnostics.
 *
 * <p><b>Wiring.</b> Configured via {@code logback-spring.xml}:
 * <pre>{@code
 * <appender name="TELEGRAM" class="uz.orientadvertise.services.infra.telegram.TelegramAppender">
 *   <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
 *     <level>WARN</level>
 *   </filter>
 * </appender>
 * }</pre>
 */
public class TelegramAppender extends AppenderBase<ILoggingEvent> {

    static final int MAX_BUFFER = 500;
    static final int STACK_LINES = 5;
    static final String[] RECURSIVE_LOGGER_PREFIXES = {
            "org.telegram",
            "uz.orientadvertise.services.infra.telegram"
    };

    /**
     * Static so events emitted before Spring context startup are still captured. The
     * forwarder reads from here once the bot is registered.
     */
    private static final Deque<TelegramLogEvent> BUFFER = new ConcurrentLinkedDeque<>();
    private static final AtomicLong DROPPED = new AtomicLong();

    /** Thread-local re-entry guard for the forwarder thread. */
    private static final ThreadLocal<Boolean> SENDING = ThreadLocal.withInitial(() -> false);

    @Override
    protected void append(ILoggingEvent event) {
        try {
            if (!shouldForward(event)) {
                return;
            }
            TelegramLogEvent formatted = format(event);
            offer(formatted);
            // Mirror to the retained buffer so /health can show recent errors AFTER
            // they've been delivered (the forward queue gets drained on send). The two
            // buffers are independent — a failure to retain doesn't affect delivery and
            // vice-versa, since RetainedErrorBuffer.append is itself no-throw.
            RetainedErrorBuffer.append(
                    Instant.ofEpochMilli(event.getTimeStamp()),
                    event.getLevel().toString(),
                    event.getLoggerName(),
                    event.getFormattedMessage(),
                    formatted.exception());
        } catch (Throwable t) {
            // The appender NEVER throws. Logging is a best-effort observer — a bug here
            // must not poison the application's primary code path.
            addError("TelegramAppender failed to enqueue event", t);
        }
    }

    private static boolean shouldForward(ILoggingEvent event) {
        // Belt-and-suspenders level filter (logback-spring.xml also has one).
        if (event.getLevel().toInt() < Level.WARN.toInt()) {
            return false;
        }
        if (Boolean.TRUE.equals(SENDING.get())) {
            return false;
        }
        String loggerName = event.getLoggerName();
        if (loggerName == null) return false;
        for (String prefix : RECURSIVE_LOGGER_PREFIXES) {
            if (loggerName.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private static TelegramLogEvent format(ILoggingEvent event) {
        String emoji = switch (event.getLevel().toInt()) {
            case 40000 -> "🔴"; // ERROR — red circle
            case 30000 -> "⚠️"; // WARN — warning sign
            default -> "ℹ️";   // INFO fallback (shouldn't reach here)
        };

        String stackSnippet = formatStack(event.getThrowableProxy());
        String exceptionLine = exceptionClassLine(event.getThrowableProxy());

        return new TelegramLogEvent(
                emoji,
                event.getLoggerName(),
                event.getLevel().toString(),
                event.getFormattedMessage(),
                exceptionLine,
                stackSnippet);
    }

    private static String exceptionClassLine(IThrowableProxy throwable) {
        if (throwable == null) return null;
        String msg = throwable.getMessage();
        return msg == null
                ? throwable.getClassName()
                : throwable.getClassName() + ": " + msg;
    }

    private static String formatStack(IThrowableProxy throwable) {
        if (throwable == null) return null;
        StackTraceElementProxy[] elements = throwable.getStackTraceElementProxyArray();
        if (elements == null || elements.length == 0) return null;
        var sb = new StringBuilder();
        int max = Math.min(STACK_LINES, elements.length);
        for (int i = 0; i < max; i++) {
            sb.append("  at ").append(elements[i].getSTEAsString()).append('\n');
        }
        if (elements.length > max) {
            sb.append("  ... (").append(elements.length - max).append(" more)");
        }
        return sb.toString();
    }

    private static void offer(TelegramLogEvent event) {
        // Drop oldest on overflow — recent context is more useful for ops than ancient
        // bootstrap noise. The DROPPED counter surfaces this in the next dispatched
        // message header.
        while (BUFFER.size() >= MAX_BUFFER) {
            BUFFER.pollFirst();
            DROPPED.incrementAndGet();
        }
        BUFFER.offerLast(event);
    }

    /** Drained by {@link TelegramLogForwarder} once the bot is registered. */
    public static TelegramLogEvent drain() {
        return BUFFER.pollFirst();
    }

    /** Total events evicted from the head while the buffer was full. */
    public static long droppedCount() {
        return DROPPED.get();
    }

    /** Buffer depth — visible for ops diagnostics (e.g. an actuator endpoint). */
    public static int bufferSize() {
        return BUFFER.size();
    }

    /** Set to true on the forwarder thread while it is dispatching to Telegram. */
    static void setSendingFlag(boolean value) {
        SENDING.set(value);
    }

    /** Test-only: clears static state between cases so tests aren't order-dependent. */
    static void resetForTests() {
        BUFFER.clear();
        DROPPED.set(0);
        SENDING.remove();
    }

    /**
     * One log event in flight, formatted into the fragments the forwarder needs to
     * stitch into a Markdown payload.
     */
    public record TelegramLogEvent(
            String emoji,
            String loggerName,
            String level,
            String message,
            String exception,   // nullable
            String stack        // nullable
    ) {}
}
