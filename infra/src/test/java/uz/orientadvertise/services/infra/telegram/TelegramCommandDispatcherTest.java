package uz.orientadvertise.services.infra.telegram;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelegramCommandDispatcherTest {

    /** Records (chatId, args) of the last invocation so tests can assert routing. */
    private static final class RecordingHandler implements TelegramCommandHandler {
        private final String name;
        final AtomicReference<Long> chat = new AtomicReference<>();
        final AtomicReference<String[]> args = new AtomicReference<>();
        int calls = 0;
        RuntimeException explode;

        RecordingHandler(String name) { this.name = name; }

        @Override public String name() { return name; }

        @Override
        public void handle(Long chatId, String[] args) {
            calls++;
            this.chat.set(chatId);
            this.args.set(args);
            if (explode != null) throw explode;
        }
    }

    @Test
    void dispatch_routesToMatchingHandler_withParsedArgs() {
        var state = new RecordingHandler("/state");
        var d = new TelegramCommandDispatcher(List.of(state));

        d.dispatch(100L, "/state verbose now");

        assertEquals(1, state.calls);
        assertEquals(100L, state.chat.get());
        assertArrayEquals(new String[] {"verbose", "now"}, state.args.get());
    }

    @Test
    void dispatch_unknownCommand_silentlyIgnored() {
        var state = new RecordingHandler("/state");
        var d = new TelegramCommandDispatcher(List.of(state));

        d.dispatch(100L, "/health");

        assertEquals(0, state.calls);
    }

    @Test
    void dispatch_stripsBotUsernameSuffix() {
        // Group-chat form: Telegram appends @botname so different bots in the same
        // group don't collide. The dispatcher must resolve the same handler.
        var state = new RecordingHandler("/state");
        var d = new TelegramCommandDispatcher(List.of(state));

        d.dispatch(100L, "/state@orient_bot");

        assertEquals(1, state.calls);
        assertArrayEquals(new String[0], state.args.get());
    }

    @Test
    void dispatch_blankOrNullText_silentlyIgnored() {
        var state = new RecordingHandler("/state");
        var d = new TelegramCommandDispatcher(List.of(state));

        d.dispatch(100L, null);
        d.dispatch(100L, "");
        d.dispatch(100L, "   ");

        assertEquals(0, state.calls);
    }

    @Test
    void dispatch_nullChatId_silentlyIgnored() {
        // Defensive: the bot already filters out null chat ids upstream, but the
        // dispatcher should still no-op rather than NPE if its caller forgets.
        var state = new RecordingHandler("/state");
        var d = new TelegramCommandDispatcher(List.of(state));

        d.dispatch(null, "/state");

        assertEquals(0, state.calls);
    }

    @Test
    void dispatch_nonCommandText_silentlyIgnored() {
        // A plain "hello" with no leading slash is not a command and shouldn't match
        // anything — it's just authorized chatter.
        var state = new RecordingHandler("/state");
        var d = new TelegramCommandDispatcher(List.of(state));

        d.dispatch(100L, "hello bot");

        assertEquals(0, state.calls);
    }

    @Test
    void dispatch_handlerThrows_dispatcherAbsorbsException() {
        // Belt-and-braces: handlers contractually shouldn't throw, but if one does the
        // dispatcher must not propagate up to the polling thread.
        var state = new RecordingHandler("/state");
        state.explode = new RuntimeException("handler bug");
        var d = new TelegramCommandDispatcher(List.of(state));

        d.dispatch(100L, "/state");
        // No assertion needed — we just need the call to return normally.
        assertEquals(1, state.calls);
    }

    @Test
    void duplicateHandlerNames_failsAtConstruction() {
        // Refactor safety: registering two handlers for the same command should fail
        // loudly at boot rather than producing flaky last-wins behaviour.
        var a = new RecordingHandler("/state");
        var b = new RecordingHandler("/state");
        assertThrows(IllegalStateException.class,
                () -> new TelegramCommandDispatcher(List.of(a, b)));
    }

    @Test
    void multipleHandlers_routedIndependently() {
        var state = new RecordingHandler("/state");
        var ping = new RecordingHandler("/ping");
        var d = new TelegramCommandDispatcher(List.of(state, ping));

        d.dispatch(100L, "/state");
        d.dispatch(200L, "/ping pong");

        assertEquals(1, state.calls);
        assertEquals(1, ping.calls);
        assertEquals(100L, state.chat.get());
        assertEquals(200L, ping.chat.get());
        assertArrayEquals(new String[] {"pong"}, ping.args.get());
    }

    @Test
    void handlersForTest_exposedAsImmutable() {
        var state = new RecordingHandler("/state");
        var d = new TelegramCommandDispatcher(List.of(state));
        var map = d.handlersForTest();
        assertEquals(1, map.size());
        // Map.copyOf returns an unmodifiable copy.
        assertThrows(UnsupportedOperationException.class, () -> map.put("/x", state));
        assertNull(map.get("/missing"));
    }
}
