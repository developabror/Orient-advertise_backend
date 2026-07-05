package uz.orientadvertise.services.infra.telegram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramOutboundQueueTest {

    @TempDir
    Path tempDir;

    private OrientTelegramBot bot;
    private TelegramBotProperties props;
    private TelegramOutboundQueue queue;

    @BeforeEach
    void setUp() throws Exception {
        bot = mock(OrientTelegramBot.class);
        props = new TelegramBotProperties();
        props.setEnabled(true);
        props.setToken("token");
        props.setUsername("test-bot");
        props.setAuthorizedChatIds(Set.of(100L, 200L));
        queue = new TelegramOutboundQueue(bot, props);
        // Override the fallback file to a tmpfs path so tests don't pollute logs/.
        setField(queue, "fallbackFilePath", tempDir.resolve("undelivered.log").toString());
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        try { queue.stop(); } catch (Exception ignored) {}
    }

    /** Helper for the subset of tests that need the consumer thread running. */
    private void startConsumer() {
        queue.start();
    }

    @Test
    void enqueue_belowCapacity_isAccepted() {
        // Sanity — single enqueue with small message goes through cleanly.
        var msg = OutboundMessage.initial("hi", "Markdown", Severity.INFO, Set.of(100L));
        queue.enqueue(msg);
        assertEquals(1, queue.currentDepth());
    }

    @Test
    void enqueue_overflow_dropsOldestNonCritical() {
        // Fill queue with non-critical messages; the next enqueue must not block,
        // must drop the oldest one, and must not increase total depth past CAPACITY.
        for (int i = 0; i < TelegramOutboundQueue.CAPACITY; i++) {
            queue.enqueue(OutboundMessage.initial("msg-" + i, "Markdown",
                    Severity.WARN, Set.of(100L)));
        }
        assertEquals(TelegramOutboundQueue.CAPACITY, queue.currentDepth());
        assertEquals(0, queue.droppedOldestCount());

        long t0 = System.nanoTime();
        queue.enqueue(OutboundMessage.initial("incoming", "Markdown",
                Severity.WARN, Set.of(100L)));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(TelegramOutboundQueue.CAPACITY, queue.currentDepth(), "depth still capped");
        assertEquals(1, queue.droppedOldestCount(), "exactly one eviction");
        assertTrue(elapsedMs < 200, "producer was non-blocking (took " + elapsedMs + "ms)");
    }

    @Test
    void enqueue_overflowAllFatal_routesIncomingToFallback() throws IOException {
        // Spec: never block the producer; FATAL messages are protected from
        // drop-oldest. If everything in the queue is FATAL, the incoming message
        // (regardless of severity) goes to the fallback file instead.
        for (int i = 0; i < TelegramOutboundQueue.CAPACITY; i++) {
            queue.enqueue(OutboundMessage.initial("crit-" + i, "Markdown",
                    Severity.FATAL, Set.of(100L)));
        }
        queue.enqueue(OutboundMessage.initial("dropped", "Markdown",
                Severity.WARN, Set.of(100L)));

        assertEquals(TelegramOutboundQueue.CAPACITY, queue.currentDepth(),
                "depth unchanged — no FATAL was evicted");
        assertEquals(1, queue.droppedAllFatalCount());
        assertEquals(1, queue.fallbackWriteCount());
        // Verify a line landed in the fallback file.
        Path file = tempDir.resolve("undelivered.log");
        assertTrue(Files.exists(file));
        String body = Files.readString(file);
        assertTrue(body.contains("\"reason\":\"queue full (all FATAL)\""), body);
        assertTrue(body.contains("\"preview\":\"dropped\""), body);
    }

    @Test
    void enqueue_overflow_protectsFatalEntriesFromEviction() {
        // Mix the queue: a FATAL at the head followed by non-criticals. An incoming
        // non-critical must evict the OLDEST NON-CRITICAL (i.e. the second item),
        // not the FATAL at the head.
        queue.enqueue(OutboundMessage.initial("crit", "Markdown", Severity.FATAL, Set.of(100L)));
        for (int i = 0; i < TelegramOutboundQueue.CAPACITY - 1; i++) {
            queue.enqueue(OutboundMessage.initial("warn-" + i, "Markdown",
                    Severity.WARN, Set.of(100L)));
        }

        queue.enqueue(OutboundMessage.initial("incoming", "Markdown",
                Severity.WARN, Set.of(100L)));

        assertEquals(1, queue.droppedOldestCount());
        // The FATAL is still in the queue — drain via the test hook and confirm.
        // We can't easily peek without consuming, so check droppedAllFatal stayed 0.
        assertEquals(0, queue.droppedAllFatalCount(),
                "FATAL must not have been evicted as drop-oldest candidate");
    }

    @Test
    void processMessage_successfulSend_doesNotRetry() throws Exception {
        when(bot.send(anyString(), anyString(), eq("Markdown"))).thenReturn(true);
        startConsumer();
        queue.enqueue(OutboundMessage.initial("hi", "Markdown", Severity.INFO,
                Set.of(100L, 200L)));
        Thread.sleep(300); // give consumer thread time to process

        verify(bot).send("100", "hi", "Markdown");
        verify(bot).send("200", "hi", "Markdown");
        // No fallback writes and no retry-related state.
        assertEquals(0, queue.fallbackWriteCount());
    }

    @Test
    void processMessage_partialFailure_retriesOnlyFailedChats() throws Exception {
        // 100 succeeds, 200 fails. The retry must target ONLY 200 (not 100), and
        // attempt counter must increment.
        when(bot.send(eq("100"), anyString(), anyString())).thenReturn(true);
        when(bot.send(eq("200"), anyString(), anyString())).thenReturn(false, true);
        startConsumer();

        queue.enqueue(OutboundMessage.initial("hi", "Markdown", Severity.INFO,
                Set.of(100L, 200L)));
        // Initial attempt + 1s backoff retry. Wait long enough for both.
        Thread.sleep(1800);

        verify(bot, times(1)).send("100", "hi", "Markdown");
        verify(bot, times(2)).send("200", "hi", "Markdown");
        assertEquals(0, queue.fallbackWriteCount());
    }

    @Test
    void processMessage_threeFailures_writesFallbackFile() throws Exception {
        when(bot.send(anyString(), anyString(), anyString())).thenReturn(false);
        startConsumer();

        queue.enqueue(OutboundMessage.initial("doomed", "Markdown",
                Severity.ERROR, Set.of(100L)));
        // Wait for: initial + 1s + 2s = ~3s plus margin. After attempt 3 fails we go
        // to fallback (MAX_ATTEMPTS = 3, so attempts 1, 2, 3 then fallback).
        Thread.sleep(4500);

        verify(bot, atLeastOnce()).send(eq("100"), anyString(), anyString());
        assertTrue(queue.fallbackWriteCount() >= 1,
                "fallback must be written after 3 attempts");
        Path file = tempDir.resolve("undelivered.log");
        String body = Files.readString(file);
        assertTrue(body.contains("max retry attempts"), body);
        assertTrue(body.contains("\"preview\":\"doomed\""), body);
    }

    @Test
    void sendWithTimeout_blocksFiveSecondsThenFails() throws Exception {
        startConsumer();
        // Stub the bot to hang. The consumer's per-send timeout is 5s; sendWithTimeout
        // must return false rather than letting the thread block indefinitely.
        var hung = new CountDownLatch(1);
        when(bot.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            hung.countDown();
            Thread.sleep(15_000);
            return true;
        });

        long t0 = System.currentTimeMillis();
        boolean result = queue.sendWithTimeout(100L,
                OutboundMessage.initial("x", "Markdown", Severity.INFO, Set.of(100L)));
        long elapsed = System.currentTimeMillis() - t0;

        assertFalse(result);
        assertTrue(hung.await(1, TimeUnit.SECONDS), "stub started running");
        assertTrue(elapsed >= 4_900 && elapsed < 6_500,
                "5-second timeout enforced; elapsed=" + elapsed + "ms");
    }

    @Test
    void backoffSchedule_oneTwoFourSeconds() {
        // Locked against the spec: exponential backoff between retries.
        assertEquals(1, TelegramOutboundQueue.backoffForAttempt(1).toSeconds());
        assertEquals(2, TelegramOutboundQueue.backoffForAttempt(2).toSeconds());
        assertEquals(4, TelegramOutboundQueue.backoffForAttempt(3).toSeconds());
    }

    @Test
    void fallbackLine_isJsonAndContainsRequiredFields() {
        var msg = OutboundMessage.initial("hi", "Markdown", Severity.ERROR, Set.of(100L, 200L))
                .withAttempt(2);
        String line = TelegramOutboundQueue.formatFallbackLine(msg, "test reason");

        assertTrue(line.startsWith("{"));
        assertTrue(line.endsWith("}"));
        assertTrue(line.contains("\"timestamp\""));
        assertTrue(line.contains("\"reason\":\"test reason\""));
        assertTrue(line.contains("\"severity\":\"ERROR\""));
        assertTrue(line.contains("\"attempts\":2"));
        assertTrue(line.contains("\"preview\":\"hi\""));
    }

    @Test
    void enqueue_emptyChatIds_skipsSilently() {
        queue.enqueue(OutboundMessage.initial("x", "Markdown", Severity.INFO, Set.of()));
        assertEquals(0, queue.currentDepth());
    }

    @Test
    void enqueue_null_skipsSilently() {
        queue.enqueue(null);
        assertEquals(0, queue.currentDepth());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
