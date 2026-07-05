package uz.orientadvertise.services.infra.telegram;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;
import uz.orientadvertise.services.domain.notification.TelegramNotifier;

/**
 * Real {@link TelegramNotifier} backed by {@link OrientTelegramBot}. Created when
 * {@code telegram.bot.enabled=true} and a non-empty token is set — see
 * {@link TelegramBotConfig}.
 *
 * <p><b>Send pipeline.</b> Every {@code sendMessage} / {@code sendMarkdown} /
 * {@code broadcastMarkdown} returns immediately. The actual HTTP call to Telegram is
 * performed on the {@link TelegramOutboundQueue} consumer thread, with the 5-second
 * timeout, retry, and fallback-file machinery defined there. This notifier is purely
 * a producer — it formats, gates (auth + rate limit), and enqueues.
 *
 * <p><b>Two-state lifecycle.</b> The notifier exists from context startup, but
 * {@link #markRegistered()} is not called until {@link TelegramBotInitializer}
 * successfully registers the bot with Telegram. Sends issued before registration
 * completes are dropped at debug level — early hooks like {@code @PostConstruct} can
 * call this without race-condition risk.
 *
 * <p><b>Authorization filter.</b> Every send checks the chat id against the
 * configured allow-list ({@link TelegramBotProperties#isAuthorized}).
 *
 * <p>Empty allow-list is logged once at construction (not per call) and turns every
 * send into a silent drop — the "don't crash on missing config" edge case.
 */
public class EnabledTelegramNotifier implements TelegramNotifier {

    private static final Logger log = LoggerFactory.getLogger(EnabledTelegramNotifier.class);

    private final TelegramBotProperties props;
    private final TelegramRateLimiter rateLimiter;
    private final TelegramOutboundQueue outboundQueue;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public EnabledTelegramNotifier(TelegramBotProperties props,
                                     TelegramRateLimiter rateLimiter,
                                     TelegramOutboundQueue outboundQueue) {
        this.props = props;
        this.rateLimiter = rateLimiter;
        this.outboundQueue = outboundQueue;
        if (props.getAuthorizedChatIds().isEmpty()) {
            log.warn("Telegram bot is enabled but TELEGRAM_BOT_AUTHORIZED_CHAT_IDS is empty — "
                    + "all sends will be silently dropped. Set the env var to at least one chat id "
                    + "to receive notifications.");
        }
    }

    /** Called by {@link TelegramBotInitializer} after a successful Telegram registration. */
    void markRegistered() {
        registered.set(true);
    }

    /** @return {@code true} once the bot is registered with Telegram. */
    public boolean isRegistered() {
        return registered.get();
    }

    @Override
    public void sendMessage(String chatId, String message) {
        enqueueSingle(chatId, message, null, Severity.INFO);
    }

    /**
     * Extended send with parse mode (Markdown / MarkdownV2 / HTML). Used by the
     * startup notifier to format the boot message.
     */
    public void sendMarkdown(String chatId, String message) {
        enqueueSingle(chatId, message, "Markdown", Severity.INFO);
    }

    private void enqueueSingle(String chatId, String message, String parseMode, Severity severity) {
        if (chatId == null || chatId.isBlank()) {
            log.debug("Telegram sendMessage called with blank chatId — skipping");
            return;
        }
        if (!registered.get()) {
            log.debug("Telegram bot not yet registered — dropping message [chatId={}]", chatId);
            return;
        }
        Long parsed;
        try {
            parsed = Long.valueOf(chatId);
        } catch (NumberFormatException e) {
            log.warn("Telegram sendMessage chatId not a valid Long: '{}' — skipping", chatId);
            return;
        }
        if (!props.isAuthorized(parsed)) {
            log.debug("Telegram sendMessage to unauthorized chat — dropping [chatId={}]", chatId);
            return;
        }
        outboundQueue.enqueue(OutboundMessage.initial(message, parseMode, severity, Set.of(parsed)));
    }

    @Override
    public void broadcastMarkdown(String message) {
        // No severity hint → treat as INFO-equivalent (rate-limited, not a bypass).
        broadcastMarkdown(message, Severity.INFO);
    }

    @Override
    public void broadcastMarkdown(String message, Severity severity) {
        if (props.getAuthorizedChatIds().isEmpty()) {
            log.debug("broadcastMarkdown skipped — no authorized chats");
            return;
        }
        if (!registered.get()) {
            log.debug("broadcastMarkdown skipped — bot not yet registered");
            return;
        }
        // Rate-limit gate: FATAL bypasses, every other severity counts toward the
        // 30/min global + 5/5min per-hash sliding windows. When suppressed, the limiter
        // also stashes a sample so TelegramSuppressedAggregator can flush a summary.
        var decision = rateLimiter.tryAcquire(message, severity);
        if (!decision.shouldSend()) {
            log.debug("broadcastMarkdown suppressed by rate limiter (severity={})", severity);
            return;
        }
        // Hand the broadcast off to the outbound queue. Single OutboundMessage holds
        // the whole authorized chat list — the consumer iterates per-chat with the
        // 5-second timeout and tracks partial success for retries.
        outboundQueue.enqueue(OutboundMessage.initial(
                message, "Markdown", severity, props.getAuthorizedChatIds()));
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
