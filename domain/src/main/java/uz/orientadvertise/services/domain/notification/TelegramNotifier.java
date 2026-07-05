package uz.orientadvertise.services.domain.notification;

/**
 * Domain port for outbound Telegram messages. Service-layer code injects this and
 * sends without depending on the {@code org.telegram:telegrambots} library — the
 * impl lives in infra.
 *
 * <p>Two implementations are wired conditionally on {@code telegram.bot.enabled}:
 * <ul>
 *   <li>{@code EnabledTelegramNotifier} — actual long-polling bot, only created when
 *       {@code telegram.bot.enabled=true} AND a non-empty token is set.</li>
 *   <li>{@code NoOpTelegramNotifier} — fallback that drops messages and reports
 *       {@link #isEnabled()} {@code false}, used in dev / test / when the feature is
 *       disabled. Callers can choose to short-circuit before formatting an expensive
 *       message by checking {@link #isEnabled()} first.</li>
 * </ul>
 *
 * <p>The port itself never throws — implementations are expected to log and swallow
 * delivery failures. A Telegram outage must not poison whichever business path
 * triggered the notification.
 */
public interface TelegramNotifier {

    /**
     * Send a plain-text message to a Telegram chat. Markdown / HTML formatting is up
     * to the implementation. No-op when the bot is disabled.
     *
     * @param chatId  target chat identifier (string form to support negative supergroup ids)
     * @param message message body
     */
    void sendMessage(String chatId, String message);

    /**
     * Render {@code message} as Markdown and dispatch to <em>every</em> authorized
     * chat. Used by {@code GlobalExceptionHandler} (500 forwards) and other broadcast
     * sites that don't have a single recipient in mind. No-op when the bot is disabled
     * or no chats are authorized.
     *
     * <p>Implementations must swallow per-chat delivery failures — a single bad chat
     * id can't be allowed to skip the rest, and a Telegram outage must not propagate
     * back to the caller.
     *
     * <p>Subject to the rate limiter wired in front of the bot — see infra-side
     * documentation. Use {@link #broadcastMarkdown(String, uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity)}
     * when a severity hint is available; this overload defaults to no severity hint
     * (treated as {@code INFO}-equivalent: rate-limited, not a bypass).
     */
    void broadcastMarkdown(String message);

    /**
     * Severity-aware broadcast. Implementations route this through the rate limiter:
     * {@code FATAL} bypasses, every other severity counts toward the per-minute and
     * per-message-hash sliding-window caps. The severity also drives summary
     * aggregation — when this call is suppressed, the summary records the message's
     * first line as the sample and the severity for the eventual flush.
     */
    void broadcastMarkdown(String message,
                           uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity severity);

    /**
     * @return {@code true} when the bot is wired and registered with Telegram, {@code false}
     *         when the feature is disabled or initialization failed. Callers can use this
     *         to skip building expensive payloads they would only throw away.
     */
    boolean isEnabled();
}
