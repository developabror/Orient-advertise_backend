package uz.orientadvertise.services.infra.telegram;

/**
 * Single-command handler invoked by {@link TelegramCommandDispatcher} when an authorized
 * inbound message starts with {@link #name()}. Implementations are responsible for
 * gathering whatever data they need and replying to the requesting chat directly via
 * {@link OrientTelegramBot#send(String, String, String)}.
 *
 * <p><b>Contract.</b> {@link #handle(Long, String[])} MUST NOT throw. The dispatcher
 * does swallow exceptions defensively, but a handler is closer to the metric / send
 * code that can fail and is better placed to translate failures into a fallback reply.
 *
 * <p><b>Bypass path.</b> Handlers send via {@code bot.send} directly — not via the
 * outbound queue — because interactive command responses have a sub-1-second latency
 * budget that the queue's 5-second per-send timeout can't guarantee. This is the third
 * documented bypass alongside {@link TelegramShutdownNotifier} and
 * {@link TelegramSuppressedAggregator}.
 */
public interface TelegramCommandHandler {

    /**
     * The command token that triggers this handler, including the leading slash —
     * e.g. {@code "/state"}. Comparison is case-sensitive and must match the first
     * whitespace-delimited token of the inbound message (after stripping any
     * {@code @botusername} suffix that Telegram appends in group chats).
     */
    String name();

    /**
     * Handle the command. {@code chatId} is the authorized requester; {@code args} are
     * the remaining whitespace-delimited tokens (empty array when the user typed only
     * the command). Implementations should reply via {@code bot.send(chatId, ...)}.
     */
    void handle(Long chatId, String[] args);
}
