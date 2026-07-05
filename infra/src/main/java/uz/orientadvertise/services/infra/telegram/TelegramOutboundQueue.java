package uz.orientadvertise.services.infra.telegram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;

/**
 * Bounded outbound queue for Telegram broadcasts. Single-threaded consumer; producers
 * never block.
 *
 * <p><b>Topology.</b>
 * <pre>
 *  [N producer threads]                [1 consumer thread]            [Telegram API]
 *         │                                    │                              │
 *         │  enqueue(msg)                      │                              │
 *         ├──────────────►  LinkedBlockingDeque(1000) ────────► poll ─────────┤
 *         │  (non-blocking)                    │              5s timeout      │
 *         │                                    │                              │
 *         │                                    │   on failure: schedule retry │
 *         │                                    │   on retryScheduler with     │
 *         │                                    │   exponential backoff (1/2/4s)
 *         │                                    │                              │
 *         │                                    │   after 3 failures →         │
 *         │                                    │   write to fallback file     │
 * </pre>
 *
 * <p><b>Capacity 1000.</b> When the deque is full, the producer drops the OLDEST
 * non-critical message to make room. {@link Severity#FATAL} entries are protected —
 * they're not chosen as drop candidates. If every entry in the deque is FATAL (rare),
 * the incoming message is dropped instead and written to the fallback file.
 * <em>Producers are never blocked.</em>
 *
 * <p><b>5-second send timeout.</b> Each {@code bot.send} runs on a tiny worker pool
 * with the consumer thread awaiting its {@link Future#get(long, TimeUnit)} for 5
 * seconds. On timeout the future is cancelled and the message goes through the retry
 * path same as any other failure. The consumer thread itself is therefore bounded to a
 * 5-second-per-message latency floor regardless of network state.
 *
 * <p><b>Retry policy.</b> Up to 3 attempts (initial + 2 retries) with exponential
 * backoff: 1s → 2s → 4s. Per-chat partial success is preserved — the retry only
 * targets the chats that failed on the previous attempt. After 3 attempts, remaining
 * chats are written to the fallback file as a JSON line and the message is discarded.
 *
 * <p><b>Bypass paths.</b> {@link TelegramShutdownNotifier} (3-second JVM-exit budget)
 * and {@link TelegramSuppressedAggregator} (must-deliver rate-limit summary) call
 * {@code bot.send} directly, bypassing this queue. Documented exceptions to the
 * "all sends through queue" rule.
 */
public class TelegramOutboundQueue {

    private static final Logger log = LoggerFactory.getLogger(TelegramOutboundQueue.class);

    public static final int CAPACITY = 1000;
    public static final int MAX_ATTEMPTS = 3;
    public static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final OrientTelegramBot bot;
    private final TelegramBotProperties props;
    private final LinkedBlockingDeque<OutboundMessage> queue = new LinkedBlockingDeque<>(CAPACITY);
    private final ReentrantLock producerLock = new ReentrantLock();

    private final AtomicLong droppedOldest = new AtomicLong();
    private final AtomicLong droppedAllFatal = new AtomicLong();
    private final AtomicLong fallbackWrites = new AtomicLong();

    private ExecutorService consumerExec;
    private ExecutorService timeoutExec;
    private ScheduledExecutorService retryScheduler;
    private volatile boolean running = true;

    @Value("${telegram.bot.fallback-file:logs/telegram-undelivered.log}")
    private String fallbackFilePath;

    /**
     * Optional. When set, the consumer filters disabled chats out of the recipient set
     * before attempting any send — disabled chats neither consume an attempt nor land
     * in the retry path.
     */
    private volatile DisabledChatRegistry disabledChatRegistry;

    public TelegramOutboundQueue(OrientTelegramBot bot, TelegramBotProperties props) {
        this.bot = bot;
        this.props = props;
    }

    public void setDisabledChatRegistry(DisabledChatRegistry registry) {
        this.disabledChatRegistry = registry;
    }

    /**
     * Bean lifecycle: invoked by Spring via {@code @Bean(initMethod = "start")} in
     * {@link TelegramBotConfig}. Starts the dedicated consumer + retry threads.
     */
    public void start() {
        consumerExec = Executors.newSingleThreadExecutor(named("telegram-sender"));
        timeoutExec = Executors.newCachedThreadPool(named("telegram-send-timeout"));
        retryScheduler = Executors.newSingleThreadScheduledExecutor(named("telegram-retry"));
        consumerExec.submit(this::consumeLoop);
        log.info("Telegram outbound queue started [capacity={}]", CAPACITY);
    }

    /** Bean lifecycle: invoked via {@code @Bean(destroyMethod = "stop")}. */
    public void stop() {
        running = false;
        if (consumerExec != null) consumerExec.shutdownNow();
        if (timeoutExec != null) timeoutExec.shutdownNow();
        if (retryScheduler != null) retryScheduler.shutdownNow();
    }

    /**
     * Producer entry point. Non-blocking. Returns immediately. The message is enqueued
     * if there is room, OR the oldest non-critical message is evicted to make room, OR
     * (when every queued entry is FATAL) the incoming message is written to the
     * fallback file and dropped.
     */
    public void enqueue(OutboundMessage msg) {
        if (msg == null || msg.remainingChatIds().isEmpty()) return;
        if (queue.offer(msg)) return;        // fast path

        producerLock.lock();
        try {
            if (queue.offer(msg)) return;    // re-check after lock
            // Find oldest non-critical and evict.
            Iterator<OutboundMessage> it = queue.iterator();
            while (it.hasNext()) {
                OutboundMessage candidate = it.next();
                if (candidate.severity() != Severity.FATAL) {
                    it.remove();
                    droppedOldest.incrementAndGet();
                    log.debug("Outbound queue full — evicted oldest non-critical to make room");
                    queue.offer(msg);
                    return;
                }
            }
            // Every entry is FATAL. We CANNOT evict a FATAL to make room (spec).
            // Drop the incoming and route to fallback so it isn't silently lost.
            droppedAllFatal.incrementAndGet();
            log.warn("Outbound queue full and entirely FATAL — incoming message routed to fallback");
            writeFallback(msg, "queue full (all FATAL)");
        } finally {
            producerLock.unlock();
        }
    }

    private void consumeLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            OutboundMessage msg;
            try {
                msg = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                processMessage(msg);
            } catch (Throwable t) {
                // Defensive: a bug in processing must NEVER kill the consumer thread.
                log.warn("Outbound consumer encountered an error: {}", t.getMessage(), t);
            }
        }
    }

    void processMessage(OutboundMessage msg) {
        Set<Long> stillFailing = new LinkedHashSet<>();
        for (Long chatId : msg.remainingChatIds()) {
            // Drop disabled chats BEFORE attempting the network call. A previously-403'd
            // chat doesn't consume an attempt, doesn't enter retry, doesn't land in the
            // fallback file. This is the spec's "endless retry storm" guard at the queue
            // level — the bot itself also short-circuits, but doing it here too keeps
            // the retry path clean.
            if (disabledChatRegistry != null && disabledChatRegistry.isDisabled(chatId)) {
                log.debug("Outbound queue skipping disabled chat [chatId={}]", chatId);
                continue;
            }
            if (sendWithTimeout(chatId, msg)) continue;
            stillFailing.add(chatId);
        }
        if (stillFailing.isEmpty()) return; // delivered to all

        int next = msg.attempt() + 1;
        if (next >= MAX_ATTEMPTS) {
            writeFallback(msg.withRemaining(stillFailing),
                    "max retry attempts (" + MAX_ATTEMPTS + ") exceeded");
            return;
        }
        Duration delay = backoffForAttempt(next);
        var retried = msg.withAttempt(next).withRemaining(stillFailing);
        log.debug("Telegram retry scheduled [attempt={}, delay={}, chats={}]",
                next, delay, stillFailing);
        retryScheduler.schedule(() -> reEnqueueRetry(retried), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Re-insert a retry at the HEAD of the deque so it goes ahead of newly-arrived
     * normal messages — it's already consumed an attempt and shouldn't be deprioritized.
     * If the deque is somehow full at this moment, the retry is sent to fallback.
     */
    private void reEnqueueRetry(OutboundMessage retry) {
        if (queue.offerFirst(retry)) return;
        // Producer-side lock not needed — offerFirst is the canonical operation;
        // if it fails the deque is genuinely saturated.
        log.warn("Retry could not be re-enqueued (queue full) — routing to fallback");
        writeFallback(retry, "queue full at retry time");
    }

    boolean sendWithTimeout(Long chatId, OutboundMessage msg) {
        Future<Boolean> future = timeoutExec.submit(() ->
                bot.send(String.valueOf(chatId), msg.text(), msg.parseMode()));
        try {
            Boolean result = future.get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(result);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.debug("Telegram send timed out [chatId={}]", chatId);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return false;
        } catch (CancellationException | ExecutionException e) {
            log.debug("Telegram send failed [chatId={}]: {}", chatId, e.getMessage());
            return false;
        }
    }

    static Duration backoffForAttempt(int attempt) {
        // attempt 1 → 1s, 2 → 2s, 3 → 4s. Cap defensively.
        long seconds = Math.min(1L << Math.max(0, attempt - 1), 60L);
        return Duration.ofSeconds(seconds);
    }

    private void writeFallback(OutboundMessage msg, String reason) {
        fallbackWrites.incrementAndGet();
        try {
            Path file = Path.of(fallbackFilePath);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            String line = formatFallbackLine(msg, reason);
            Files.writeString(file, line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // The fallback file IS the last-resort persistent record; if it can't be
            // written we have to give up. Log and move on.
            log.error("Telegram fallback file write failed [{}]: {}", fallbackFilePath, e.getMessage());
        }
    }

    static String formatFallbackLine(OutboundMessage msg, String reason) {
        // Hand-rolled JSON keeps the infra slim — no Jackson dependency required.
        // Long messages truncated to keep the fallback file readable.
        String text = msg.text() == null ? "" : msg.text();
        String preview = text.length() > 500 ? text.substring(0, 500) + "…" : text;
        return ("{\"timestamp\":\"%s\",\"reason\":\"%s\",\"severity\":\"%s\","
                + "\"attempts\":%d,\"chatIds\":%s,\"preview\":\"%s\"}").formatted(
                Instant.now().toString(),
                escape(reason),
                msg.severity() == null ? "INFO" : msg.severity().name(),
                msg.attempt(),
                msg.remainingChatIds().toString(),
                escape(preview));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static java.util.concurrent.ThreadFactory named(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    // -------- ops accessors --------

    public int currentDepth() { return queue.size(); }
    public long droppedOldestCount() { return droppedOldest.get(); }
    public long droppedAllFatalCount() { return droppedAllFatal.get(); }
    public long fallbackWriteCount() { return fallbackWrites.get(); }

    /** Accessor for tests so we can drive the consumer loop directly. */
    void runOneFromQueueForTest() {
        OutboundMessage msg = queue.poll();
        if (msg != null) processMessage(msg);
    }
}
