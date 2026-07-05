package uz.orientadvertise.services.infra.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder;
import uz.orientadvertise.services.infra.telegram.TelegramAppender.TelegramLogEvent;

/**
 * Drains {@link TelegramAppender}'s static buffer and dispatches each entry to every
 * authorized chat once the bot is registered. Runs on Spring's task scheduler so we
 * inherit the existing {@code @EnableScheduling} infrastructure rather than spinning up
 * a dedicated executor.
 *
 * <p><b>Thread-safety with the appender.</b> While this method is sending, it sets
 * {@link TelegramAppender#setSendingFlag} so any log statement emitted by the Telegram
 * client (or downstream OkHttp / etc.) on the SAME thread is short-circuited at the
 * appender. The package-name filter catches most cases; the thread-local catches the
 * rest.
 *
 * <p><b>Back-pressure.</b> The drain is bounded per tick to {@value #MAX_PER_TICK} —
 * if the buffer is full of bootstrap noise, we don't spam Telegram or block the
 * scheduler thread for minutes. Anything still queued waits for the next tick.
 *
 * <p><b>Best effort.</b> Failures during send are logged at {@code DEBUG} (any louder
 * and we'd risk feeding the recursion loop the appender catches). The bot's own
 * {@code send} already swallows and logs {@code TelegramApiException}, so by the time
 * we see one here it's a programming error worth diagnosing offline.
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
public class TelegramLogForwarder {

    private static final Logger log = LoggerFactory.getLogger(TelegramLogForwarder.class);

    static final int MAX_PER_TICK = 20;

    private final EnabledTelegramNotifier notifier;
    private final TelegramBotProperties props;

    public TelegramLogForwarder(EnabledTelegramNotifier notifier,
                                  TelegramBotProperties props) {
        this.notifier = notifier;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${telegram.bot.log-forward-delay-ms:2000}",
                initialDelayString = "${telegram.bot.log-forward-initial-delay-ms:5000}")
    public void drainAndForward() {
        // Wait for registration. Pre-registration events stay buffered.
        if (!notifier.isRegistered()) {
            return;
        }
        if (props.getAuthorizedChatIds().isEmpty()) {
            // Nothing to send to — discard everything currently queued so the buffer
            // doesn't grow unbounded across the lifetime of the JVM.
            while (TelegramAppender.drain() != null) { /* drop */ }
            return;
        }

        TelegramAppender.setSendingFlag(true);
        try {
            for (int i = 0; i < MAX_PER_TICK; i++) {
                TelegramLogEvent event = TelegramAppender.drain();
                if (event == null) return;
                dispatch(event);
            }
        } finally {
            TelegramAppender.setSendingFlag(false);
        }
    }

    private void dispatch(TelegramLogEvent event) {
        // Multi-chunk so a long stack trace (or a flood of metadata) can split into
        // back-to-back messages instead of being silently truncated by Telegram.
        // Routed through the rate-limited broadcastMarkdown — the appender's job is
        // to capture every WARN/ERROR; the rate limiter's job is to decide which ones
        // actually leave the JVM. The severity is preserved so a flood of identical
        // ERRORs gets aggregated into a 5-minute summary instead of flooding the chat.
        var chunks = renderChunks(event);
        var severity = mapSeverity(event.level());
        for (String chunk : chunks) {
            try {
                notifier.broadcastMarkdown(chunk, severity);
            } catch (Throwable t) {
                log.debug("Telegram log forward failed: {}", t.getMessage());
            }
        }
    }

    /**
     * Render a log event into one or more Telegram-Markdown chunks via the shared
     * {@link TelegramMessageBuilder}. The builder handles markdown escaping, per-field
     * 500-char truncation, and 4096-char chunking automatically.
     */
    static java.util.List<String> renderChunks(TelegramLogEvent event) {
        TelegramMessageBuilder.Severity severity = mapSeverity(event.level());
        var b = TelegramMessageBuilder.builder()
                .severity(severity)
                .title(event.loggerName());
        if (event.message() != null && !event.message().isEmpty()) {
            b.text(event.message());
        } else {
            b.text("(no message)");
        }
        if (event.exception() != null) {
            b.section("exception", event.exception());
        }
        if (event.stack() != null) {
            b.codeBlock("stack", event.stack());
        }
        return b.buildChunks();
    }

    private static TelegramMessageBuilder.Severity mapSeverity(String level) {
        return switch (level) {
            case "ERROR" -> TelegramMessageBuilder.Severity.ERROR;
            case "WARN" -> TelegramMessageBuilder.Severity.WARN;
            default -> TelegramMessageBuilder.Severity.INFO;
        };
    }
}
