package uz.orientadvertise.services.infra.telegram;

import java.util.Set;

import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;

/**
 * One logical Telegram broadcast in flight through {@link TelegramOutboundQueue}.
 * Carries everything the single-threaded consumer needs to fan out to chats and
 * (on failure) re-enqueue with progress preserved:
 *
 * <ul>
 *   <li>{@link #text}, {@link #parseMode} — the payload to send</li>
 *   <li>{@link #severity} — drives the queue's overflow eviction policy
 *       ({@link Severity#FATAL} is protected from drop-oldest)</li>
 *   <li>{@link #remainingChatIds} — chats not yet successfully delivered to. On
 *       partial success the retry only re-targets the failed subset.</li>
 *   <li>{@link #attempt} — 0 on first enqueue, incremented per retry. After
 *       {@link TelegramOutboundQueue#MAX_ATTEMPTS} the remaining chats are written
 *       to the fallback file.</li>
 * </ul>
 *
 * <p>Records are immutable; the consumer uses {@link #withAttempt} and
 * {@link #withRemaining} to produce the next state for the retry path.
 */
public record OutboundMessage(
        String text,
        String parseMode,
        Severity severity,
        Set<Long> remainingChatIds,
        int attempt
) {

    public OutboundMessage {
        // Defensive: never store a mutable Set instance.
        remainingChatIds = remainingChatIds == null
                ? Set.of()
                : Set.copyOf(remainingChatIds);
        if (severity == null) severity = Severity.INFO;
    }

    public static OutboundMessage initial(String text, String parseMode,
                                            Severity severity, Set<Long> chatIds) {
        return new OutboundMessage(text, parseMode, severity, chatIds, 0);
    }

    public OutboundMessage withAttempt(int next) {
        return new OutboundMessage(text, parseMode, severity, remainingChatIds, next);
    }

    public OutboundMessage withRemaining(Set<Long> remaining) {
        return new OutboundMessage(text, parseMode, severity, remaining, attempt);
    }
}
