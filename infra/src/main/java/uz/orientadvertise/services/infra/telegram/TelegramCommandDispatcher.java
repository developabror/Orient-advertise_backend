package uz.orientadvertise.services.infra.telegram;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes authorized inbound text to a {@link TelegramCommandHandler} by matching the
 * first whitespace-delimited token against {@link TelegramCommandHandler#name()}.
 *
 * <p><b>Authorization is upstream.</b> {@link OrientTelegramBot#onUpdateReceived}
 * already drops messages from non-authorized chats before invoking the dispatcher, so
 * every {@link #dispatch(Long, String)} call is, by definition, from an authorized
 * sender. The dispatcher does not re-check.
 *
 * <p><b>Group-chat suffix.</b> When the bot is added to a group, Telegram delivers
 * commands as {@code /state@orient_bot}. The dispatcher strips the {@code @suffix} so
 * group and DM commands resolve to the same handler.
 *
 * <p><b>Unknown command → silent ignore.</b> Matches the bot's overall posture of not
 * advertising its existence: an unknown command (typo, probing, foreign bot's slash)
 * gets no reply and no log above debug.
 *
 * <p><b>Handler exceptions.</b> The dispatcher wraps the handler call in a top-level
 * {@code try / catch (Throwable)} as a defensive belt-and-braces. Handlers themselves
 * are required by their contract to never throw, but a bug must not propagate up to
 * the polling thread and kill the bot.
 */
public class TelegramCommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommandDispatcher.class);

    private final Map<String, TelegramCommandHandler> handlers;

    public TelegramCommandDispatcher(Collection<TelegramCommandHandler> handlers) {
        var map = new HashMap<String, TelegramCommandHandler>();
        for (TelegramCommandHandler h : handlers) {
            if (map.put(h.name(), h) != null) {
                // Duplicate command name — last-wins, but fail loudly so a refactor that
                // accidentally registers two handlers for the same command is caught at
                // boot rather than producing flaky runtime behavior.
                throw new IllegalStateException(
                        "Duplicate Telegram command handler for " + h.name());
            }
        }
        this.handlers = Map.copyOf(map);
    }

    public void dispatch(Long chatId, String text) {
        if (chatId == null || text == null || text.isBlank()) return;

        String[] tokens = text.trim().split("\\s+");
        String cmd = tokens[0];
        // Strip optional @botusername suffix from the first token (group-chat form).
        int at = cmd.indexOf('@');
        if (at > 0) cmd = cmd.substring(0, at);

        TelegramCommandHandler handler = handlers.get(cmd);
        if (handler == null) {
            log.debug("Unknown Telegram command [chatId={}, cmd={}] — ignored", chatId, cmd);
            return;
        }

        String[] args = tokens.length > 1
                ? Arrays.copyOfRange(tokens, 1, tokens.length)
                : new String[0];
        try {
            handler.handle(chatId, args);
        } catch (Throwable t) {
            // Defensive — handlers are contractually no-throw. If one slips, log and
            // keep the polling thread alive.
            log.warn("Telegram command handler {} threw: {}", cmd, t.toString(), t);
        }
    }

    /** Visible for tests. */
    Map<String, TelegramCommandHandler> handlersForTest() {
        return handlers;
    }
}
