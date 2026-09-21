package uz.orientadvertise.services.infra.storage;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one and only place ffmpeg concurrency is decided.
 *
 * <p><b>Why one pool.</b> Before v1.0.132 transcodes ran on the shared {@code auditExecutor} — whose
 * documented rejection policy is <i>silently drop</i>, so a rejected transcode left a file stuck in
 * {@code UPLOADED} with no diagnostic trail at all — while "urgent" uploads ran on a second pool
 * with {@code CallerRunsPolicy}, which on overflow executed the entire multi-minute pipeline inline
 * on the Tomcat request thread. Two pools also meant the real ceiling on concurrent encodes was the
 * sum of their widths, which is precisely how a memory budget gets blown. One pool makes total
 * concurrency a single number that can be reasoned about and tuned.
 *
 * <p><b>Why the width is planned, not fixed.</b> See {@link TranscodeCapacityPlanner}: the safe
 * number is a property of the host's CPU count and memory budget, so it is derived at startup and
 * logged. {@code app.video.transcode.concurrency} overrides it outright when an operator knows
 * better. A bigger server therefore scales up on its own — no code change, no rebuild.
 *
 * <p><b>Why priority, and why not {@code @Async}.</b> Urgent uploads must not queue behind a backlog.
 * Tasks are enqueued into a {@link PriorityBlockingQueue} via {@link ThreadPoolExecutor#execute} —
 * <i>not</i> {@code submit()} — so the object sitting in the queue is our own comparable
 * {@link PriorityTask} rather than a {@code FutureTask} wrapper that would hide the priority from
 * the comparator. Spring's {@code @Async} always goes through {@code submit()}, which is why
 * {@code FFmpegTranscoder} dispatches through this class directly instead. A monotonically
 * increasing sequence number breaks ties, so ordering is strict FIFO within a priority band and
 * starvation is impossible for a fixed number of urgent jobs.
 *
 * <p>Rejection is <b>loud</b>: the queue is bounded, and an overflow logs at ERROR. The row keeps
 * its claim's lease, so {@code TranscodeSweeper} re-drives it once the lease expires — a dropped
 * task is a delay, never a permanently stuck file.
 */
@Component
public class TranscodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(TranscodeExecutor.class);

    /** Lower number runs first. Urgent uploads jump the queue at any pool width. */
    public static final int PRIORITY_URGENT = 0;
    public static final int PRIORITY_NORMAL = 10;

    private final ThreadPoolExecutor executor;
    private final AtomicLong sequence = new AtomicLong();
    private final int concurrency;
    private final int queueCapacity;
    private final int shutdownGraceSeconds;

    public TranscodeExecutor(
            @Value("${app.video.transcode.concurrency:0}") int configuredConcurrency,
            @Value("${app.video.transcode.max-concurrency:8}") int maxConcurrency,
            @Value("${app.video.transcode.job-memory-mb:320}") int jobMemoryMb,
            @Value("${app.video.transcode.reserve-mb:256}") int reserveMb,
            @Value("${app.video.transcode.queue-capacity:200}") int queueCapacity,
            @Value("${app.video.transcode.shutdown-grace-seconds:30}") int shutdownGraceSeconds) {

        int cpus = HostResources.availableCpus();
        long containerMemory = HostResources.containerMemoryLimitBytes();
        long heap = HostResources.maxHeapBytes();

        this.concurrency = TranscodeCapacityPlanner.plan(configuredConcurrency, cpus, containerMemory,
                heap, jobMemoryMb, reserveMb, maxConcurrency);
        this.queueCapacity = Math.max(1, queueCapacity);
        this.shutdownGraceSeconds = Math.max(1, shutdownGraceSeconds);

        this.executor = new ThreadPoolExecutor(
                this.concurrency, this.concurrency,
                0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(Math.min(this.queueCapacity, 64)),
                namedDaemonThreadFactory("transcode-"),
                (task, exec) -> log.error(
                        "Transcode task REJECTED — pool saturated (concurrency={}, queued={}); "
                                + "the content row keeps its lease and TranscodeSweeper will re-drive it",
                        this.concurrency, exec.getQueue().size()));

        log.info("Transcode pool sized: concurrency={} ({}), cpus={}, containerMemory={} MiB, "
                        + "maxHeap={} MiB, jobBudget={} MiB, queueCapacity={}",
                this.concurrency,
                configuredConcurrency > 0 ? "configured via app.video.transcode.concurrency" : "auto-planned",
                cpus, containerMemory / (1024 * 1024), heap / (1024 * 1024), jobMemoryMb, this.queueCapacity);
    }

    /**
     * Enqueue a transcode. Returns immediately.
     *
     * <p>The queue bound is enforced here rather than by the {@code PriorityBlockingQueue} itself,
     * which is unbounded by construction — checking the depth before offering keeps the rejection
     * handler meaningful and stops a runaway backlog from consuming heap. A tiny over-admission
     * under concurrent submits is deliberate: the alternative is a lock, and the cost of being one
     * task over the line is nil.
     *
     * @return {@code true} when the task was accepted, {@code false} when it was rejected (already
     *         logged at ERROR; the caller's row stays claimed for the sweeper)
     */
    public boolean submit(Runnable task, int priority, String description) {
        if (executor.getQueue().size() >= queueCapacity) {
            log.error("Transcode task REJECTED — queue at capacity {} [{}]; "
                    + "the content row keeps its lease and TranscodeSweeper will re-drive it",
                    queueCapacity, description);
            return false;
        }
        try {
            executor.execute(new PriorityTask(priority, sequence.incrementAndGet(), task, description));
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The handler above already logged; this only fires on a shutdown race.
            return false;
        }
    }

    /** Planned (or configured) number of concurrent encodes. */
    public int getConcurrency() {
        return concurrency;
    }

    /** Tasks waiting to start — surfaced by the Telegram {@code /health} command. */
    public int getQueueDepth() {
        return executor.getQueue().size();
    }

    /** Encodes running right now. */
    public int getActiveCount() {
        return executor.getActiveCount();
    }

    /** Configured queue bound, for capacity reporting. */
    public int getQueueCapacity() {
        return queueCapacity;
    }

    /**
     * Drain on shutdown instead of discarding. A {@code docker compose up -d} otherwise throws away
     * whatever was queued, reproducing the very failure this class exists to prevent. In-flight
     * encodes past the grace period are abandoned — their rows keep the lease and the sweeper
     * reclaims them on the next boot.
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownGraceSeconds, TimeUnit.SECONDS)) {
                log.warn("Transcode pool did not drain within {}s — {} task(s) abandoned; "
                                + "their leases expire and the sweeper reclaims them",
                        shutdownGraceSeconds, executor.getQueue().size());
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static ThreadFactory namedDaemonThreadFactory(String prefix) {
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        return runnable -> {
            var thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Queue entry carrying its own ordering. {@code priority} first, then {@code sequence} so equal
     * priorities stay FIFO — without the tiebreaker a {@link PriorityBlockingQueue} gives no
     * ordering guarantee among equal elements, and a steady trickle of normal jobs could reorder
     * arbitrarily.
     */
    record PriorityTask(int priority, long sequence, Runnable delegate, String description)
            implements Runnable, Comparable<PriorityTask> {

        @Override
        public void run() {
            delegate.run();
        }

        @Override
        public int compareTo(PriorityTask other) {
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
