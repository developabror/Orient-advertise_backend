package uz.orientadvertise.services.infra.telegram;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramAppenderTest {

    private TelegramAppender appender;
    private Logger appLogger;

    @BeforeEach
    void setUp() {
        TelegramAppender.resetForTests();
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new TelegramAppender();
        appender.setContext(context);
        appender.start();
        appLogger = context.getLogger("uz.orientadvertise.services.test");
    }

    @AfterEach
    void tearDown() {
        appender.stop();
        TelegramAppender.resetForTests();
    }

    @Test
    void warnEvent_isBuffered() {
        appender.doAppend(buildEvent(Level.WARN, "uz.orientadvertise.services.test", "boom"));

        var ev = TelegramAppender.drain();
        assertNotNull(ev);
        assertEquals("WARN", ev.level());
        assertEquals("uz.orientadvertise.services.test", ev.loggerName());
        assertEquals("boom", ev.message());
        assertEquals("⚠️", ev.emoji());
    }

    @Test
    void errorEvent_carriesRedEmoji() {
        appender.doAppend(buildEvent(Level.ERROR, "uz.orientadvertise.services.test", "kaboom"));

        var ev = TelegramAppender.drain();
        assertNotNull(ev);
        assertEquals("🔴", ev.emoji());
    }

    @Test
    void infoEvent_isFilteredOut() {
        // ThresholdFilter normally blocks INFO at the appender input, but the unit test
        // calls doAppend directly. The appender's defensive level check inside append()
        // catches this case.
        appender.doAppend(buildEvent(Level.INFO, "uz.orientadvertise.services.test", "noise"));

        assertNull(TelegramAppender.drain());
    }

    @Test
    void recursiveLoggerName_telegramPackage_isDropped() {
        // The recursion-prevention guard. Logback's own ThresholdFilter wouldn't help
        // because the Telegram client legitimately logs at WARN/ERROR — we filter by
        // logger NAME, not level.
        appender.doAppend(buildEvent(Level.ERROR, "org.telegram.telegrambots.SomeClient",
                "Connection refused"));

        assertNull(TelegramAppender.drain());
    }

    @Test
    void recursiveLoggerName_ourTelegramPackage_isDropped() {
        appender.doAppend(buildEvent(Level.ERROR,
                "uz.orientadvertise.services.infra.telegram.OrientTelegramBot", "send failed"));

        assertNull(TelegramAppender.drain());
    }

    @Test
    void threadLocalSendingFlag_dropsRecursiveLogs() {
        // While the forwarder is dispatching it sets the SENDING flag. Anything logged
        // on that thread (e.g. OkHttp at debug, but level filter notwithstanding —
        // imagine a non-telegram-package logger called from inside the send path) must
        // be skipped to prevent loops.
        TelegramAppender.setSendingFlag(true);
        try {
            appender.doAppend(buildEvent(Level.ERROR, "okhttp3.internal.SomeThing", "io error"));
            assertNull(TelegramAppender.drain());
        } finally {
            TelegramAppender.setSendingFlag(false);
        }
    }

    @Test
    void exceptionAndStack_capturedAndTruncatedToFiveLines() {
        var deeplyNested = makeException(20); // 20-frame stack
        var event = buildEvent(Level.ERROR, "uz.orientadvertise.services.test", "kaboom");
        event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(deeplyNested));
        appender.doAppend(event);

        var ev = TelegramAppender.drain();
        assertNotNull(ev);
        assertNotNull(ev.exception());
        assertTrue(ev.exception().contains("kaboom"));
        long stackLines = ev.stack().lines().filter(l -> l.startsWith("  at ")).count();
        assertEquals(TelegramAppender.STACK_LINES, stackLines);
        assertTrue(ev.stack().contains("more)"), "remainder summary present: " + ev.stack());
    }

    @Test
    void noException_stackAndExceptionAreNull() {
        appender.doAppend(buildEvent(Level.WARN, "uz.orientadvertise.services.test", "just a warn"));

        var ev = TelegramAppender.drain();
        assertNull(ev.exception());
        assertNull(ev.stack());
    }

    @Test
    void overflowingBuffer_dropsOldestAndCounts() {
        // The buffer is bounded so a logspam-during-bootstrap scenario doesn't OOM. We
        // drop the OLDEST first because recent context is more useful for an operator
        // diagnosing the current incident.
        for (int i = 0; i < TelegramAppender.MAX_BUFFER + 5; i++) {
            appender.doAppend(buildEvent(Level.WARN,
                    "uz.orientadvertise.services.test", "msg-" + i));
        }
        assertEquals(TelegramAppender.MAX_BUFFER, TelegramAppender.bufferSize());
        assertEquals(5, TelegramAppender.droppedCount());
        // Oldest were dropped — the head should now be msg-5.
        var first = TelegramAppender.drain();
        assertEquals("msg-5", first.message());
    }

    @Test
    void appenderNeverThrows_evenOnNullLoggerName() {
        var event = buildEvent(Level.WARN, null, "msg");
        appender.doAppend(event);
        assertNull(TelegramAppender.drain());
    }

    private static LoggingEvent buildEvent(Level level, String loggerName, String message) {
        var event = new LoggingEvent();
        event.setLevel(level);
        event.setLoggerName(loggerName);
        event.setMessage(message);
        return event;
    }

    private static RuntimeException makeException(int depth) {
        if (depth == 0) return new RuntimeException("kaboom");
        return makeException(depth - 1);
    }
}
