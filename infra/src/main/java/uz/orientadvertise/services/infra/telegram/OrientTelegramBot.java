package uz.orientadvertise.services.infra.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import uz.orientadvertise.services.infra.telegram.TelegramMetrics.FailureReason;

/**
 * The long-polling bot. Owns the Telegram session and exposes a small
 * {@link #send(String, String, String)} method the notifier delegates to.
 *
 * <p><b>Inbound authorization.</b> {@link #onUpdateReceived} consults the authorized
 * chat-id allow-list on every update. Messages from chats outside the allow-list are
 * <em>silently dropped</em> — no response, no error log, not even a debug entry that
 * could leak the bot's existence to a fishing attempt. Authorized inbound traffic is
 * forwarded to the optional {@link TelegramCommandDispatcher} (set after construction
 * via {@link #setCommandDispatcher}); when no dispatcher has been wired the bot remains
 * outbound-only and authorized inbound is a debug-logged no-op.
 *
 * <p>The bot is constructed unregistered — registration with Telegram is handled by
 * {@link TelegramBotInitializer} on {@link org.springframework.boot.context.event.ApplicationReadyEvent}
 * with retry, so a Telegram outage at boot doesn't block application startup.
 */
public class OrientTelegramBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(OrientTelegramBot.class);

    private final TelegramBotProperties props;
    /**
     * Set after construction by {@link TelegramBotConfig} so the bot's constructor stays
     * dependency-free (the dispatcher has its own dependencies — handlers, BuildProperties
     * — that are wired separately). Volatile because {@code onUpdateReceived} runs on the
     * Telegram polling thread while the setter runs on the Spring context-init thread.
     */
    private volatile TelegramCommandDispatcher commandDispatcher;
    /**
     * Set after construction. Optional — when null, the bot still works but no metrics
     * are recorded. Same setter pattern as the dispatcher to keep the constructor lean
     * and the test slices unaffected by the new feature.
     */
    private volatile TelegramMetrics metrics;
    private volatile TelegramFailureRateMonitor failureRateMonitor;
    private volatile DisabledChatRegistry disabledChatRegistry;

    public OrientTelegramBot(TelegramBotProperties props) {
        super(props.getToken());
        this.props = props;
    }

    /** Wired post-construction by {@link TelegramBotConfig}; may be left unset in tests. */
    public void setCommandDispatcher(TelegramCommandDispatcher commandDispatcher) {
        this.commandDispatcher = commandDispatcher;
    }

    public void setMetrics(TelegramMetrics metrics) {
        this.metrics = metrics;
    }

    public void setFailureRateMonitor(TelegramFailureRateMonitor monitor) {
        this.failureRateMonitor = monitor;
    }

    public void setDisabledChatRegistry(DisabledChatRegistry registry) {
        this.disabledChatRegistry = registry;
    }

    @Override
    public String getBotUsername() {
        return props.getUsername();
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update == null || !update.hasMessage() || update.getMessage().getChatId() == null) {
            return;
        }
        Long chatId = update.getMessage().getChatId();
        if (!props.isAuthorized(chatId)) {
            // Silent ignore — never respond, never log above trace. The spec calls this
            // out specifically: an unauthorized sender must not learn the bot exists.
            // Trace-level so noisy debug logs don't reveal probing chat ids in shared
            // logs either.
            log.trace("Dropping unauthorized inbound update [chatId={}]", chatId);
            return;
        }
        // Authorized — hand off to the command dispatcher when one has been wired.
        // Snapshot the volatile reference so the setter can't swap it mid-dispatch.
        TelegramCommandDispatcher dispatcher = this.commandDispatcher;
        if (log.isDebugEnabled()) {
            log.debug("Authorized inbound message [chatId={}, length={}]",
                    chatId,
                    update.getMessage().hasText() ? update.getMessage().getText().length() : 0);
        }
        if (dispatcher != null && update.getMessage().hasText()) {
            dispatcher.dispatch(chatId, update.getMessage().getText());
        }
    }

    /**
     * Send {@code text} to {@code chatId} with optional {@code parseMode} (e.g.
     * {@code "Markdown"} or {@code "MarkdownV2"}). Logs and swallows
     * {@link TelegramApiException} — Telegram outages must not propagate into the
     * calling business path.
     *
     * <p><b>Disabled-chat short-circuit.</b> If the chat id has been previously disabled
     * (Telegram returned 403 → registered in {@link DisabledChatRegistry}), this method
     * returns {@code false} immediately without attempting the network call. The result
     * is NOT counted as a failure in metrics — the chat is intentionally skipped, not
     * broken.
     *
     * <p><b>403 detection.</b> A {@link TelegramApiRequestException} with error code
     * 403 means the bot was kicked, blocked, or removed. We register the chat id with
     * {@link DisabledChatRegistry} so future broadcasts skip it without retrying — that
     * is the spec's "endless retry storm" guard.
     *
     * @return {@code true} on successful send, {@code false} on Telegram API failure or
     *         skipped-because-disabled
     */
    public boolean send(String chatId, String text, String parseMode) {
        Long parsedChatId = parseChatId(chatId);
        if (parsedChatId != null && disabledChatRegistry != null
                && disabledChatRegistry.isDisabled(parsedChatId)) {
            log.debug("Skipping send to disabled chat [chatId={}]", chatId);
            return false;
        }
        try {
            var msg = SendMessage.builder().chatId(chatId).text(text);
            if (parseMode != null && !parseMode.isBlank()) {
                msg.parseMode(parseMode);
            }
            execute(msg.build());
            if (metrics != null) metrics.recordSuccess();
            if (failureRateMonitor != null) failureRateMonitor.recordSuccess();
            return true;
        } catch (TelegramApiException e) {
            FailureReason reason = classifyFailure(e);
            String detail = e.getMessage();
            if (metrics != null) metrics.recordFailure(reason, detail);
            if (failureRateMonitor != null) failureRateMonitor.recordFailure();
            if (reason == FailureReason.FORBIDDEN_403 && parsedChatId != null
                    && disabledChatRegistry != null) {
                disabledChatRegistry.disable(parsedChatId,
                        detail == null ? "Telegram 403 Forbidden" : detail);
            }
            log.warn("Telegram send failed [chatId={}, reason={}]: {}", chatId, reason, detail);
            return false;
        }
    }

    /**
     * Classify a Telegram API failure for metric tagging and disabled-chat detection.
     * The library's {@link TelegramApiRequestException} carries the HTTP status code
     * via {@link TelegramApiRequestException#getErrorCode()}; everything else falls into
     * a coarse bucket based on the message text or class.
     */
    static FailureReason classifyFailure(TelegramApiException e) {
        if (e instanceof TelegramApiRequestException req) {
            // getErrorCode returns Integer — null-safe unbox so an unset code from a
            // partially-constructed exception doesn't NPE on the hot path.
            Integer code = req.getErrorCode();
            if (code != null && code == 403) return FailureReason.FORBIDDEN_403;
            // 4xx other than 403 / 5xx / unknown are all "OTHER" — only 403 has a
            // dedicated handling rule (chat disable).
            return FailureReason.OTHER;
        }
        // Non-request exceptions are typically wrappers around IOException / SocketTimeoutException.
        Throwable cause = e.getCause();
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (cause instanceof java.util.concurrent.TimeoutException
                || message.contains("timeout") || message.contains("timed out")) {
            return FailureReason.TIMEOUT;
        }
        if (cause instanceof java.io.IOException
                || message.contains("network") || message.contains("connection")
                || message.contains("unable to execute")) {
            return FailureReason.NETWORK;
        }
        return FailureReason.OTHER;
    }

    private static Long parseChatId(String chatId) {
        if (chatId == null || chatId.isBlank()) return null;
        try {
            return Long.parseLong(chatId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Plain-text send, no parse mode. */
    public boolean send(String chatId, String text) {
        return send(chatId, text, null);
    }
}
