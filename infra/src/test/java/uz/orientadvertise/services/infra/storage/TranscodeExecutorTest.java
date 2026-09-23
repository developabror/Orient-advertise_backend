package uz.orientadvertise.services.infra.storage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscodeExecutorTest {

    /** Width 1, queue capacity {@code queueCapacity}; the rest are the production defaults. */
    private static TranscodeExecutor singleWidth(int queueCapacity) {
        return new TranscodeExecutor(1, 8, 320, 256, queueCapacity, 1);
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(TranscodeExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @Test
    void urgentTask_overtakesQueuedNormalTasks() throws Exception {
        // The whole reason for the priority queue: an urgent upload must not wait behind a backlog.
        // With one worker occupied, everything else queues — and the urgent job must come out first.
        var executor = singleWidth(50);
        try {
            var occupyWorker = new CountDownLatch(1);
            var workerStarted = new CountDownLatch(1);
            List<String> order = new CopyOnWriteArrayList<>();
            var done = new CountDownLatch(3);

            executor.submit(() -> {
                workerStarted.countDown();
                try {
                    occupyWorker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                order.add("blocker");
                done.countDown();
            }, TranscodeExecutor.PRIORITY_NORMAL, "blocker");
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS), "worker never started");

            executor.submit(() -> { order.add("normal"); done.countDown(); },
                    TranscodeExecutor.PRIORITY_NORMAL, "normal");
            executor.submit(() -> { order.add("urgent"); done.countDown(); },
                    TranscodeExecutor.PRIORITY_URGENT, "urgent");

            occupyWorker.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS), "tasks did not finish: " + order);
            assertEquals(List.of("blocker", "urgent", "normal"), order);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void equalPriority_staysFifo() throws Exception {
        // A PriorityBlockingQueue gives NO ordering guarantee among equal elements, so without the
        // sequence tiebreaker a steady trickle of normal jobs could reorder arbitrarily.
        var executor = singleWidth(50);
        try {
            var occupyWorker = new CountDownLatch(1);
            var workerStarted = new CountDownLatch(1);
            List<String> order = new CopyOnWriteArrayList<>();
            var done = new CountDownLatch(5);

            executor.submit(() -> {
                workerStarted.countDown();
                try {
                    occupyWorker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                done.countDown();
            }, TranscodeExecutor.PRIORITY_NORMAL, "blocker");
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));

            for (int i = 1; i <= 4; i++) {
                String name = "job" + i;
                executor.submit(() -> { order.add(name); done.countDown(); },
                        TranscodeExecutor.PRIORITY_NORMAL, name);
            }

            occupyWorker.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS), "tasks did not finish: " + order);
            assertEquals(List.of("job1", "job2", "job3", "job4"), order);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void queueOverflow_isRejectedLoudly_notSilentlyDropped() throws Exception {
        // The v1.0.132 incident's second failure mode: transcodes ran on a pool whose rejection
        // policy was an empty lambda, so a saturated pool produced a permanently stuck content row
        // with ZERO diagnostic trail. Rejection must be visible, and must name the recovery path.
        var captured = attachAppender();
        var executor = singleWidth(1);
        try {
            var occupyWorker = new CountDownLatch(1);
            var workerStarted = new CountDownLatch(1);
            executor.submit(() -> {
                workerStarted.countDown();
                try {
                    occupyWorker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, TranscodeExecutor.PRIORITY_NORMAL, "blocker");
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));

            assertTrue(executor.submit(() -> { }, TranscodeExecutor.PRIORITY_NORMAL, "fills-queue"),
                    "the first queued task fits within capacity 1");
            assertFalse(executor.submit(() -> { }, TranscodeExecutor.PRIORITY_NORMAL, "transcode id=42"),
                    "the second must be refused, not silently swallowed");

            boolean logged = captured.list.stream()
                    .anyMatch(e -> e.getLevel() == Level.ERROR
                            && e.getFormattedMessage().contains("REJECTED")
                            && e.getFormattedMessage().contains("TranscodeSweeper"));
            assertTrue(logged, "rejection must log at ERROR and name the sweeper; captured: " + captured.list);

            occupyWorker.countDown();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void concurrency_honoursAnExplicitOverride() {
        var executor = new TranscodeExecutor(3, 8, 320, 256, 10, 1);
        try {
            assertEquals(3, executor.getConcurrency());
            assertEquals(10, executor.getQueueCapacity());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void autoPlannedConcurrency_isAlwaysUsable() {
        // Whatever machine CI runs on, the pool must come up with at least one worker. (A host too
        // small to fit ANY encode is the one exception — see below — and CI is not one.)
        var executor = new TranscodeExecutor(0, 8, 320, 256, 10, 1);
        try {
            assertTrue(executor.getConcurrency() >= 1, "auto plan must never yield 0 workers here");
            assertTrue(executor.getConcurrency() <= 8, "auto plan must respect the cap");
            assertFalse(executor.isDisabled());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void aHostThatCannotFitAnEncode_refusesWorkInsteadOfKillingTheJvm() {
        // VG-17. A reserve larger than the whole container drives the memory budget negative, so
        // the planner returns 0. Starting an encode anyway would charge ffmpeg's RSS to the JVM's
        // cgroup and get the BACKEND OOM-killed; refusing keeps the file in UPLOADED, which the
        // sweeper and /api/health both surface.
        org.junit.jupiter.api.Assumptions.assumeTrue(HostResources.containerMemoryLimitBytes() > 0,
                "this host reports no memory limit, so the planner cannot reason about memory");
        var executor = new TranscodeExecutor(0, 8, 320, Integer.MAX_VALUE, 10, 1);
        var appender = attachAppender();
        try {
            assertTrue(executor.isDisabled());
            assertEquals(0, executor.getConcurrency());

            var ran = new java.util.concurrent.atomic.AtomicBoolean(false);
            boolean accepted = executor.submit(() -> ran.set(true), TranscodeExecutor.PRIORITY_NORMAL, "job");

            assertFalse(accepted, "a disabled pool must reject, so the caller releases the row");
            assertFalse(ran.get(), "nothing may run");
            assertEquals(0, executor.getQueueDepth());
            assertEquals(0, executor.getActiveCount());
            assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("disabled")),
                    "the refusal must be loud — an operator has to know why nothing transcodes");
        } finally {
            executor.shutdown();   // must not throw with no pool behind it
        }
    }

    @Test
    void shutdown_drainsQueuedWork_ratherThanDiscardingIt() throws Exception {
        // `docker compose up -d` used to throw away whatever was queued — for a transcode that is a
        // permanently stuck content file, which is the exact failure this release removes.
        var executor = singleWidth(50);
        var ran = new CountDownLatch(3);
        for (int i = 0; i < 3; i++) {
            executor.submit(ran::countDown, TranscodeExecutor.PRIORITY_NORMAL, "job");
        }
        executor.shutdown();
        assertTrue(ran.await(5, TimeUnit.SECONDS), "queued work must run before shutdown completes");
    }

    @Test
    void submitAfterShutdown_reportsTheDrop() {
        // The rejection handler used to only log, so submit() said "accepted" for a task that was
        // thrown away — and the caller kept the file marked as held, hiding it from the sweeper.
        var executor = singleWidth(50);
        executor.shutdown();

        assertFalse(executor.submit(() -> { }, TranscodeExecutor.PRIORITY_NORMAL, "transcode id=9"));
    }
}
