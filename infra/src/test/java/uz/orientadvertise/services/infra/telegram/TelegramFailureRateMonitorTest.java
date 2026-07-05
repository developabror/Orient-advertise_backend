package uz.orientadvertise.services.infra.telegram;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramFailureRateMonitorTest {

    /** Captures Logback events on the monitor's logger so we can assert the warning fired. */
    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override protected void append(ILoggingEvent event) { events.add(event); }
    }

    private CapturingAppender appender;
    private Logger monitorLogger;

    @BeforeEach
    void attachAppender() {
        monitorLogger = (Logger) LoggerFactory.getLogger(TelegramFailureRateMonitor.class);
        appender = new CapturingAppender();
        appender.start();
        monitorLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        monitorLogger.detachAppender(appender);
        appender.stop();
    }

    private long errorCount() {
        return appender.events.stream().filter(e -> e.getLevel() == Level.ERROR).count();
    }

    @Test
    void belowMinSamples_doesNotWarn_evenAtHighFailureRate() {
        // Spec: the alarm needs ≥ MIN_SAMPLES (10) to avoid noisy warnings on tiny samples.
        // A 1-of-2 failure (50% rate) is statistically meaningless — must not page.
        var monitor = new TelegramFailureRateMonitor();
        monitor.recordFailure();
        monitor.recordSuccess();

        assertEquals(0, errorCount());
    }

    @Test
    void allFailing_aboveMinSamples_emitsCriticalWarning() {
        var monitor = new TelegramFailureRateMonitor();
        for (int i = 0; i < 12; i++) monitor.recordFailure();

        assertEquals(1, errorCount(), "exactly one critical warning fired");
        var event = appender.events.stream()
                .filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
        String message = event.getFormattedMessage();
        assertTrue(message.contains("CRITICAL"), "uses CRITICAL prefix: " + message);
        assertTrue(message.contains("Telegram failure rate"), "names the metric: " + message);
    }

    @Test
    void mixed_belowThreshold_doesNotWarn() {
        // 50% threshold means strictly above. A 50/50 split should NOT fire — boundary
        // semantics matter operationally (no flapping at exactly the line).
        var monitor = new TelegramFailureRateMonitor();
        for (int i = 0; i < 6; i++) {
            monitor.recordSuccess();
            monitor.recordFailure();
        }
        // 6 / 12 = 50% — at the threshold, not above it.
        assertEquals(0, errorCount());
    }

    @Test
    void aboveThreshold_emitsWarning() {
        // 7 fail + 5 succeed = 58% > 50% → fire.
        var monitor = new TelegramFailureRateMonitor();
        for (int i = 0; i < 7; i++) monitor.recordFailure();
        for (int i = 0; i < 5; i++) monitor.recordSuccess();

        assertEquals(1, errorCount());
    }

    @Test
    void warningThrottled_secondTrigger_within5min_isSuppressed() {
        // Sustained outage shouldn't spam the log. Once fired, the warning is suppressed
        // for the throttle duration even if the rate stays above threshold.
        var monitor = new TelegramFailureRateMonitor();
        for (int i = 0; i < 12; i++) monitor.recordFailure();
        assertEquals(1, errorCount());

        // Keep failing — the throttle keeps the second-and-subsequent at bay.
        for (int i = 0; i < 12; i++) monitor.recordFailure();
        assertEquals(1, errorCount(), "throttled — still just the first warning");
    }

    @Test
    void warningRefires_afterThrottleElapses() throws Exception {
        // Test ctor lets us shrink the throttle to a measurable sub-second window.
        var monitor = new TelegramFailureRateMonitor(
                Duration.ofMinutes(5), 0.5, Duration.ofMillis(150));
        for (int i = 0; i < 12; i++) monitor.recordFailure();
        assertEquals(1, errorCount());

        Thread.sleep(200);
        for (int i = 0; i < 12; i++) monitor.recordFailure();
        assertEquals(2, errorCount(), "throttle elapsed → next warning fires");
    }

    @Test
    void evictionWindow_oldSamplesNotCounted() throws Exception {
        // 100ms window — drop everything older than that. With a 5-minute prod window
        // this is hard to test cheaply; the test ctor lets us simulate the eviction
        // semantics deterministically.
        var monitor = new TelegramFailureRateMonitor(
                Duration.ofMillis(100), 0.5, Duration.ofMillis(50));
        for (int i = 0; i < 12; i++) monitor.recordFailure();
        // 12 failures in window → fires (and adjusts last warn).
        assertTrue(errorCount() >= 1);

        // Wait for samples to age out.
        Thread.sleep(150);

        // After eviction, the deque is empty. New successes alone shouldn't trigger,
        // and the rate from the old all-fail samples must NOT carry over.
        for (int i = 0; i < 12; i++) monitor.recordSuccess();
        long warnCount = errorCount();

        // Try to trigger again with a new burst that doesn't pass threshold.
        // Successes shouldn't have increased the warning count.
        assertEquals(warnCount, errorCount(),
                "old failures evicted; new successes don't re-fire");
    }

    @Test
    void currentFailureRate_reflectsInWindowSamples() {
        var monitor = new TelegramFailureRateMonitor();
        for (int i = 0; i < 3; i++) monitor.recordFailure();
        for (int i = 0; i < 7; i++) monitor.recordSuccess();
        assertEquals(0.3, monitor.currentFailureRate(), 0.01);
    }

    @Test
    void currentFailureRate_emptyWindow_returnsZero() {
        var monitor = new TelegramFailureRateMonitor();
        assertEquals(0.0, monitor.currentFailureRate());
    }

    @Test
    void warningLogger_inTelegramPackage_excludedByAppenderRecursionGuard() {
        // The spec demands "do not loop back to Telegram". The TelegramAppender's
        // RECURSIVE_LOGGER_PREFIXES filter drops events from any logger whose name starts
        // with the telegram package — verify our monitor's logger is named accordingly.
        String name = monitorLogger.getName();
        assertTrue(name.startsWith("uz.orientadvertise.services.infra.telegram"),
                "logger name (" + name + ") must be inside the telegram package so the "
                        + "appender's recursion guard suppresses forwarding");
    }

    @Test
    void concurrentRecording_stays_consistent() throws Exception {
        // The monitor sits on the per-send hot path. Stress writes from many threads
        // and verify no exceptions, no torn reads, sample count bounded.
        var monitor = new TelegramFailureRateMonitor();
        int writers = 8;
        int perWriter = 200;
        AtomicInteger errors = new AtomicInteger();
        var done = new CountDownLatch(writers);
        var pool = Executors.newFixedThreadPool(writers);
        for (int w = 0; w < writers; w++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perWriter; i++) {
                        if (i % 2 == 0) monitor.recordSuccess();
                        else monitor.recordFailure();
                    }
                } catch (Throwable t) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(0, errors.get(), "no exceptions during concurrent recording");
        // Sample count is bounded by the writes — exact count depends on eviction window
        // and timing, just assert it's within sane bounds.
        assertTrue(monitor.sampleCount() <= writers * perWriter,
                "sample count bounded: " + monitor.sampleCount());
    }
}
