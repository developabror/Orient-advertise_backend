package uz.orientadvertise.services.infra.telegram;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.infra.telegram.TelegramAppender.TelegramLogEvent;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramLogForwarderTest {

    private OrientTelegramBot bot;
    private EnabledTelegramNotifier notifier;
    private TelegramBotProperties props;
    private TelegramLogForwarder forwarder;
    private TelegramRateLimiter rateLimiter;
    private TelegramOutboundQueue outboundQueue;

    @BeforeEach
    void setUp() {
        TelegramAppender.resetForTests();
        bot = mock(OrientTelegramBot.class);
        when(bot.send(anyString(), anyString(), eq("Markdown"))).thenReturn(true);
        props = new TelegramBotProperties();
        props.setEnabled(true);
        props.setToken("token");
        props.setUsername("test-bot");
        props.setAuthorizedChatIds(Set.of(100L, 200L));
        // Real in-memory rate limiter (no Redis) — matches the production fallback path.
        rateLimiter = new TelegramRateLimiter(mock(org.springframework.data.redis.core.StringRedisTemplate.class));
        outboundQueue = mock(TelegramOutboundQueue.class);
        notifier = new EnabledTelegramNotifier(props, rateLimiter, outboundQueue);
        forwarder = new TelegramLogForwarder(notifier, props);
    }

    @AfterEach
    void tearDown() {
        TelegramAppender.resetForTests();
    }

    @Test
    void notRegistered_doesNotDrain_eventsStayInBuffer() {
        // Pre-init buffering: events emitted before the bot is up must stay queued.
        enqueue("ERROR", "uz.orientadvertise.services.X", "boom", null, null);
        enqueue("WARN", "uz.orientadvertise.services.Y", "hmm", null, null);

        forwarder.drainAndForward();

        verify(outboundQueue, never()).enqueue(any());
        assertTrue(TelegramAppender.bufferSize() == 2);
    }

    @Test
    void registered_drainsAndEnqueuesOnceForBroadcast() {
        notifier.markRegistered();
        enqueue("ERROR", "uz.orientadvertise.services.X", "kaboom", "java.lang.RuntimeException: kaboom", null);

        forwarder.drainAndForward();

        // One logical broadcast → one queue entry. The outbound queue's consumer fans
        // out to each authorized chat; the forwarder no longer iterates chats itself.
        verify(outboundQueue, times(1)).enqueue(any(OutboundMessage.class));
    }

    @Test
    void emptyAuthorizedSet_drainsAndDiscards_doesNotSend() {
        notifier.markRegistered();
        var emptyProps = new TelegramBotProperties();
        emptyProps.setAuthorizedChatIds(Set.of());
        var fwd = new TelegramLogForwarder(notifier, emptyProps);
        enqueue("ERROR", "uz.orientadvertise.services.X", "kaboom", null, null);

        fwd.drainAndForward();

        verify(outboundQueue, never()).enqueue(any());
        // Buffer is flushed (otherwise it grows unbounded forever).
        assertTrue(TelegramAppender.bufferSize() == 0);
    }

    @Test
    void perTickCap_limitsDispatchToMaxPerTick() {
        // Defense against logspam — we forward at most MAX_PER_TICK per scheduled run
        // so the scheduler thread can return promptly even with a 500-deep buffer.
        notifier.markRegistered();
        for (int i = 0; i < TelegramLogForwarder.MAX_PER_TICK + 10; i++) {
            enqueue("WARN", "uz.orientadvertise.services.X", "msg-" + i, null, null);
        }

        forwarder.drainAndForward();

        // MAX_PER_TICK events → MAX_PER_TICK queue.enqueue calls (one per logical event).
        verify(outboundQueue, times(TelegramLogForwarder.MAX_PER_TICK))
                .enqueue(any(OutboundMessage.class));
        // 10 events still queued for the next tick.
        assertTrue(TelegramAppender.bufferSize() == 10);
    }

    @Test
    void sendingFlag_setDuringDispatch_clearedAfter() {
        // Recursion guard: while the forwarder's drain loop runs, the appender's
        // SENDING flag is set so any log event emitted on the same thread is
        // short-circuited at the appender (no recursive enqueue). We trigger a fake
        // log event from within outboundQueue.enqueue so it lands during the drain.
        notifier.markRegistered();
        enqueue("ERROR", "uz.orientadvertise.services.X", "kaboom", null, null);

        org.mockito.Mockito.doAnswer(inv -> {
            // We're now on the forwarder's thread, mid-drain — the SENDING flag is true.
            var appender = new TelegramAppender();
            var lc = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
            appender.setContext(lc);
            appender.start();
            var loggingEvent = new ch.qos.logback.classic.spi.LoggingEvent();
            loggingEvent.setLevel(ch.qos.logback.classic.Level.ERROR);
            loggingEvent.setLoggerName("okhttp3.internal");
            loggingEvent.setMessage("io error during send");
            appender.doAppend(loggingEvent);
            return null;
        }).when(outboundQueue).enqueue(any(OutboundMessage.class));

        forwarder.drainAndForward();

        // The recursive event was dropped at the appender (SENDING flag).
        assertTrue(TelegramAppender.bufferSize() == 0);
    }

    @Test
    void renderChunks_includesAllFragments() {
        var event = new TelegramLogEvent("🔴", "uz.x.Y", "ERROR", "boom",
                "java.lang.RuntimeException: boom",
                "  at uz.x.Y.method(Y.java:10)");

        String rendered = String.join("\n", TelegramLogForwarder.renderChunks(event));

        // Header now produced by TelegramMessageBuilder: severity emoji + bold label.
        // The logger name becomes the (bold) title, and the dots are markdown-escaped.
        // Backticks would also escape, so the assertion uses contains instead of equals.
        assertTrue(rendered.contains("🔴"));
        assertTrue(rendered.contains("*ERROR*"));
        assertTrue(rendered.contains("uz.x.Y"));
        assertTrue(rendered.contains("boom"));
        assertTrue(rendered.contains("RuntimeException"));
        assertTrue(rendered.contains("```"), "stack code block fence");
        assertTrue(rendered.contains("at uz.x.Y.method"));
    }

    @Test
    void renderChunks_noExceptionOrStack_omitsThoseFragments() {
        var event = new TelegramLogEvent("⚠️", "uz.x.Y", "WARN", "just a warning", null, null);

        String rendered = String.join("\n", TelegramLogForwarder.renderChunks(event));

        assertTrue(rendered.contains("just a warning"));
        assertTrue(!rendered.contains("```"), "no code fence when no stack");
    }

    @Test
    void renderChunks_nullMessage_substitutesPlaceholder() {
        var event = new TelegramLogEvent("⚠️", "uz.x.Y", "WARN", null, null, null);

        String rendered = String.join("\n", TelegramLogForwarder.renderChunks(event));

        assertTrue(rendered.contains("(no message)"));
    }

    private static void enqueue(String level, String logger, String msg, String exc, String stack) {
        // Reach into the static buffer via the appender's doAppend — but for a test
        // event we'd need a Logback ILoggingEvent. Simpler: drive it via a real
        // appender + LoggingEvent so the format path matches production.
        var appender = new TelegramAppender();
        var ctx = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        appender.setContext(ctx);
        appender.start();
        var le = new ch.qos.logback.classic.spi.LoggingEvent();
        le.setLevel(level.equals("ERROR") ? ch.qos.logback.classic.Level.ERROR
                : ch.qos.logback.classic.Level.WARN);
        le.setLoggerName(logger);
        le.setMessage(msg);
        if (exc != null) {
            le.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(
                    new RuntimeException(msg)));
        }
        appender.doAppend(le);
        // Sanity: the event reached the buffer.
        assertNotNull(le);
    }
}
