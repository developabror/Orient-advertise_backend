package uz.orientadvertise.services.infra.telegram;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@link OrientTelegramBot#onUpdateReceived} contract: messages from non-authorized
 * chats are silent-dropped — no response, no error, no log above trace level. The
 * spec calls this out specifically: "Unauthorized user sending a command receives no
 * response (silent ignore) to avoid revealing bot existence."
 *
 * <p>Direct unit-level test of the override; we don't need to spin up a real Telegram
 * session.
 */
class OrientTelegramBotAuthorizationTest {

    private OrientTelegramBot bot(Set<Long> allowed) {
        var props = new TelegramBotProperties();
        props.setEnabled(true);
        props.setToken("token-test");
        props.setUsername("test-bot");
        props.setAuthorizedChatIds(allowed);
        return new OrientTelegramBot(props);
    }

    @Test
    void unauthorizedChat_isSilentlyDropped_noThrow() {
        // Just confirms the call returns without throwing — there is no observable
        // side effect to assert on (no response, no log above trace, by design).
        var b = bot(Set.of(100L));
        b.onUpdateReceived(updateFromChat(999L));
    }

    @Test
    void authorizedChat_doesNotThrow() {
        // Authorized inbound is also a no-op for this outbound-only bot — but the code
        // path is different (logs at debug, doesn't drop at trace). Just confirms it
        // runs cleanly.
        var b = bot(Set.of(100L));
        b.onUpdateReceived(updateFromChat(100L));
    }

    @Test
    void emptyAllowList_dropsEverything() {
        // Pairs with the "don't crash on empty allow-list" edge case — every inbound
        // is unauthorized, so all are dropped silently.
        var b = bot(Set.of());
        b.onUpdateReceived(updateFromChat(100L));
        b.onUpdateReceived(updateFromChat(-1L));
    }

    @Test
    void nullUpdateOrMissingMessage_isHandledGracefully() {
        // Telegram occasionally delivers updates without a message (channel posts,
        // edited messages, callback queries). Bot must not NPE.
        var b = bot(Set.of(100L));
        b.onUpdateReceived(null);

        var noMessage = mock(Update.class);
        when(noMessage.hasMessage()).thenReturn(false);
        b.onUpdateReceived(noMessage);
    }

    @Test
    void authorizedCommand_isDispatched_whenDispatcherWired() {
        // Wired dispatcher → authorized chat → text → dispatch is called with the
        // exact text. The bot's job is to gate access; routing is the dispatcher's job.
        var b = bot(Set.of(100L));
        var handler = new CapturingHandler("/state");
        b.setCommandDispatcher(new TelegramCommandDispatcher(List.of(handler)));

        b.onUpdateReceived(updateFromChat(100L, "/state"));

        org.junit.jupiter.api.Assertions.assertEquals(1, handler.calls);
        org.junit.jupiter.api.Assertions.assertEquals(100L, handler.lastChat);
    }

    @Test
    void unauthorizedCommand_isNotDispatched() {
        // The most security-relevant case: a non-allowed chat sends a /state. The bot
        // must short-circuit BEFORE the dispatcher runs — otherwise an attacker could
        // probe the bot by command rather than just by message.
        var b = bot(Set.of(100L));
        var handler = new CapturingHandler("/state");
        b.setCommandDispatcher(new TelegramCommandDispatcher(List.of(handler)));

        b.onUpdateReceived(updateFromChat(999L, "/state"));

        org.junit.jupiter.api.Assertions.assertEquals(0, handler.calls);
    }

    @Test
    void noDispatcherWired_authorizedInbound_stillReturnsCleanly() {
        // Backwards compatibility: pre-1.0.66 callers and tests don't wire a dispatcher.
        // The bot must not NPE — onUpdateReceived just no-ops the dispatch step.
        var b = bot(Set.of(100L));
        // No setCommandDispatcher() call.
        b.onUpdateReceived(updateFromChat(100L, "/state"));
    }

    private static Update updateFromChat(long chatId) {
        return updateFromChat(chatId, "/status");
    }

    private static Update updateFromChat(long chatId, String text) {
        var update = mock(Update.class);
        var message = mock(Message.class);
        when(update.hasMessage()).thenReturn(true);
        when(update.getMessage()).thenReturn(message);
        when(message.getChatId()).thenReturn(chatId);
        when(message.hasText()).thenReturn(true);
        when(message.getText()).thenReturn(text);
        return update;
    }

    private static final class CapturingHandler implements TelegramCommandHandler {
        private final String name;
        int calls = 0;
        Long lastChat;
        CapturingHandler(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public void handle(Long chatId, String[] args) {
            calls++;
            lastChat = chatId;
        }
    }
}
