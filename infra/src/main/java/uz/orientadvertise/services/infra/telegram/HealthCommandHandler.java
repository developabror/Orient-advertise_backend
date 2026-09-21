package uz.orientadvertise.services.infra.telegram;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import javax.sql.DataSource;

import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import uz.orientadvertise.services.common.util.DiskSpace;
import uz.orientadvertise.services.infra.storage.MinioFailureClassifier;
import uz.orientadvertise.services.infra.storage.MinioHealthStatus;
import uz.orientadvertise.services.infra.storage.TranscodeExecutor;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;
import uz.orientadvertise.services.infra.diagnostic.RetainedErrorBuffer;
import uz.orientadvertise.services.infra.diagnostic.RetainedErrorBuffer.RetainedError;

/**
 * Handles {@code /health} — comprehensive system status report covering 9 sub-checks:
 * Postgres ping, Redis ping, MinIO bucket list, FFmpeg worker queue depth, device count
 * by status, open-incident count by priority, JVM memory, disk space on the data volume,
 * and the last 5 errors from the past hour.
 *
 * <p><b>Why a single command for so many checks?</b> Operators want one button to press
 * when something feels off — splitting into nine slash commands trades a coherent view
 * for needless ceremony. The whole report is one Telegram message.
 *
 * <p><b>Parallel execution.</b> All checks fire simultaneously on a dedicated bounded
 * pool ({@link #healthExec}). They're independent — no Postgres state needed for the
 * Redis check, no MinIO state needed for incident counts. Sequential execution would
 * mean a single hung component (e.g. Redis pinging an unreachable host for 2 seconds)
 * stretches the response past the 10s budget. Parallel collapses the worst case.
 *
 * <p><b>Per-check 2-second timeout.</b> Each individual check is bounded by
 * {@link CompletableFuture#orTimeout(long, TimeUnit)} — after 2 seconds, the future
 * resolves exceptionally with {@link TimeoutException} and the corresponding row is
 * marked DOWN with reason {@code "timeout"}. The underlying task may continue to run
 * (Java can't truly kill a thread mid-I/O) but its result is discarded. This is the
 * spec's "any health check sub-component timeout 2 seconds" guarantee.
 *
 * <p><b>Overall 10-second budget.</b> The {@link #handle} method waits up to 10 seconds
 * for {@link CompletableFuture#allOf} to settle. Since each individual check resolves
 * within 2 seconds, the worst case for the batch is 2 seconds (everything in parallel),
 * not 18. The 10s figure is a safety net — defensive against {@code orTimeout} jitter,
 * scheduler stalls, or one of the inner Callables throwing during result extraction.
 *
 * <p><b>Each row succeeds independently.</b> A failing check (DB unreachable, MinIO
 * 503, etc.) collapses to a {@code DOWN — reason} row and the rest of the report
 * still goes out. The spec demands the response always succeed; one bad component
 * cannot collapse eight others.
 *
 * <p><b>Bypass path.</b> Like {@code /state}, the response goes via {@code bot.send}
 * directly — bypassing the outbound queue. Interactive command responses can't tolerate
 * the queue's 5-second per-send timeout floor when our budget is 10 seconds total
 * (with most of that already consumed by health checks themselves).
 */
public class HealthCommandHandler implements TelegramCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(HealthCommandHandler.class);

    static final String COMMAND = "/health";
    static final Duration PER_CHECK_TIMEOUT = Duration.ofSeconds(2);
    static final Duration OVERALL_TIMEOUT = Duration.ofSeconds(10);
    static final Duration ERROR_WINDOW = Duration.ofHours(1);
    static final int ERROR_LIMIT = 5;
    static final ZoneId UTC_PLUS_5 = ZoneId.of("Asia/Karachi");
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Status of one component. {@code DEGRADED} = up but indicating soft trouble. */
    public enum CheckStatus { UP, DEGRADED, DOWN }

    /**
     * Result row for the report. {@code detail} is the rendered single-line summary
     * (e.g. {@code "online=42, offline=3"}); {@code reason} populated only on DOWN.
     */
    public record CheckResult(String name, CheckStatus status, String detail) {
        static CheckResult up(String name, String detail) {
            return new CheckResult(name, CheckStatus.UP, detail);
        }
        static CheckResult degraded(String name, String detail) {
            return new CheckResult(name, CheckStatus.DEGRADED, detail);
        }
        static CheckResult down(String name, String reason) {
            return new CheckResult(name, CheckStatus.DOWN, reason);
        }
    }

    private final OrientTelegramBot bot;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<MinioClient> minioProvider;
    private final ObjectProvider<DeviceStatusViewRepository> statusViewRepoProvider;
    private final ObjectProvider<IncidentRepository> incidentRepoProvider;
    private final ObjectProvider<TranscodeExecutor> transcodeExecProvider;
    private final ObjectProvider<ThreadPoolTaskExecutor> auditExecProvider;
    private final MinioHealthStatus minioHealthStatus;
    private final String dataVolumePath;

    /**
     * Dedicated pool — sized for the 9 parallel checks plus a slot for the orchestrator.
     * Daemon threads so a hung check doesn't block JVM shutdown. Cached pool with idle
     * reaping so the steady-state cost is zero when no operator is asking.
     */
    private final ExecutorService healthExec = Executors.newFixedThreadPool(10, r -> {
        Thread t = new Thread(r, "telegram-health");
        t.setDaemon(true);
        return t;
    });

    public HealthCommandHandler(OrientTelegramBot bot,
                                  ObjectProvider<DataSource> dataSourceProvider,
                                  ObjectProvider<StringRedisTemplate> redisProvider,
                                  ObjectProvider<MinioClient> minioProvider,
                                  ObjectProvider<DeviceStatusViewRepository> statusViewRepoProvider,
                                  ObjectProvider<IncidentRepository> incidentRepoProvider,
                                  ObjectProvider<TranscodeExecutor> transcodeExecProvider,
                                  ObjectProvider<ThreadPoolTaskExecutor> auditExecProvider,
                                  MinioHealthStatus minioHealthStatus,
                                  String dataVolumePath) {
        this.bot = bot;
        this.dataSourceProvider = dataSourceProvider;
        this.redisProvider = redisProvider;
        this.minioProvider = minioProvider;
        this.statusViewRepoProvider = statusViewRepoProvider;
        this.incidentRepoProvider = incidentRepoProvider;
        this.transcodeExecProvider = transcodeExecProvider;
        this.auditExecProvider = auditExecProvider;
        this.minioHealthStatus = minioHealthStatus;
        this.dataVolumePath = dataVolumePath == null || dataVolumePath.isBlank() ? "." : dataVolumePath;
    }

    @Override
    public String name() {
        return COMMAND;
    }

    @Override
    public void handle(Long chatId, String[] args) {
        // Outer no-throw envelope: even if the orchestration explodes, the user gets
        // SOMETHING. Same posture as /state.
        try {
            List<CheckResult> results = runAllChecks();
            String payload = renderPayload(results);
            bot.send(String.valueOf(chatId), payload, "Markdown");
        } catch (Throwable t) {
            log.warn("/health unexpected failure: {}", t.toString(), t);
            try {
                bot.send(String.valueOf(chatId),
                        "⚠️ /health failed unexpectedly — check application logs");
            } catch (Throwable ignored) {
                // Last-resort fallback only — bot.send already swallows TelegramApiException.
            }
        }
    }

    /**
     * Submit all 9 checks in parallel. Each future is bounded by 2s via
     * {@link CompletableFuture#orTimeout}. The whole batch is awaited up to 10s — once
     * elapsed, any check that hasn't settled is reported as a "budget exceeded" DOWN.
     */
    List<CheckResult> runAllChecks() {
        Instant start = Instant.now();

        record NamedFuture(String name, CompletableFuture<CheckResult> future) {}
        List<NamedFuture> futures = new ArrayList<>();

        futures.add(new NamedFuture("postgres", submit("postgres", this::checkPostgres)));
        futures.add(new NamedFuture("redis", submit("redis", this::checkRedis)));
        futures.add(new NamedFuture("minio", submit("minio", this::checkMinio)));
        futures.add(new NamedFuture("ffmpeg", submit("ffmpeg", this::checkFFmpegQueue)));
        futures.add(new NamedFuture("devices", submit("devices", this::checkDevices)));
        futures.add(new NamedFuture("incidents", submit("incidents", this::checkIncidents)));
        futures.add(new NamedFuture("jvm-mem", submit("jvm-mem", this::checkJvmMemory)));
        futures.add(new NamedFuture("disk", submit("disk", this::checkDisk)));
        futures.add(new NamedFuture("errors", submit("errors", this::checkRecentErrors)));

        CompletableFuture<?>[] arr = futures.stream()
                .map(NamedFuture::future)
                .toArray(CompletableFuture[]::new);

        try {
            CompletableFuture.allOf(arr).get(OVERALL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Overall budget blown. Any future not yet done is "budget exceeded".
            log.warn("/health overall {}s budget exceeded — partial results returned",
                    OVERALL_TIMEOUT.toSeconds());
        } catch (Exception e) {
            log.warn("/health orchestration error: {}", e.toString());
        }

        List<CheckResult> results = new ArrayList<>();
        for (NamedFuture nf : futures) {
            results.add(extractOrTimeout(nf.name(), nf.future()));
        }
        log.debug("/health completed in {}ms", Duration.between(start, Instant.now()).toMillis());
        return results;
    }

    /**
     * Submit a check with the per-check 2s timeout. Any thrown exception becomes a
     * DOWN row — never propagates. Result extraction is in {@link #extractOrTimeout}.
     */
    private CompletableFuture<CheckResult> submit(String name, Supplier<CheckResult> check) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return check.get();
            } catch (Throwable t) {
                return CheckResult.down(name, t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, healthExec).orTimeout(PER_CHECK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private CheckResult extractOrTimeout(String name, CompletableFuture<CheckResult> f) {
        if (!f.isDone()) {
            f.cancel(true);
            return CheckResult.down(name, "budget exceeded (10s overall)");
        }
        try {
            return f.getNow(CheckResult.down(name, "no result"));
        } catch (Throwable t) {
            // orTimeout completes exceptionally with TimeoutException — that's the
            // most common landing here. Anything else is also a DOWN.
            return CheckResult.down(name, "timeout (" + PER_CHECK_TIMEOUT.toSeconds() + "s)");
        }
    }

    // ---------- individual checks ----------

    CheckResult checkPostgres() {
        DataSource ds = dataSourceProvider.getIfAvailable();
        if (ds == null) return CheckResult.down("postgres", "no DataSource bean");
        long t0 = System.nanoTime();
        try (Connection c = ds.getConnection()) {
            // isValid timeout is in seconds; we set 1s so the JDBC driver itself enforces
            // a tighter bound than the surrounding orTimeout — defense in depth.
            boolean ok = c.isValid(1);
            long pingMs = (System.nanoTime() - t0) / 1_000_000;
            return ok
                    ? CheckResult.up("postgres", "ping " + pingMs + "ms")
                    : CheckResult.down("postgres", "isValid=false");
        } catch (Throwable t) {
            return CheckResult.down("postgres", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    CheckResult checkRedis() {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) return CheckResult.down("redis", "no Redis bean");
        long t0 = System.nanoTime();
        try {
            String pong = redis.execute((org.springframework.data.redis.connection.RedisConnection conn) -> conn.ping());
            long pingMs = (System.nanoTime() - t0) / 1_000_000;
            return "PONG".equalsIgnoreCase(pong)
                    ? CheckResult.up("redis", "ping " + pingMs + "ms")
                    : CheckResult.down("redis", "unexpected reply: " + pong);
        } catch (Throwable t) {
            return CheckResult.down("redis", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Live MinIO probe — and, since v1.0.144, a <b>writer</b> of {@link MinioHealthStatus}.
     *
     * <p>An operator typing {@code /health} while storage is degraded is performing exactly the
     * round-trip the scheduled {@link uz.orientadvertise.services.infra.storage.MinioHealthProbe}
     * performs. Reporting "minio: UP, 4 bucket(s)" in the chat while the application went on
     * refusing every upload because a stale flag said otherwise is the kind of contradiction that
     * costs an hour of an incident. So the probe's verdict is fed back: a successful list heals the
     * latch, a failure records why. Both are no-ops when the status already agrees.
     *
     * <p><b>A failure only degrades when {@link MinioFailureClassifier} says the server is
     * broken.</b> {@code listBuckets} can fail with {@code AccessDenied} — a perfectly healthy
     * MinIO refusing these credentials — and degrading on that would 503 every upload and every
     * device {@code /sync} in the fleet because an operator typed a slash command. The chat row
     * still reports DOWN: the check did fail, and the operator needs to see that.
     */
    CheckResult checkMinio() {
        MinioClient minio = minioProvider.getIfAvailable();
        // Not a probe result — it proves nothing about whether MinIO is reachable, so it must not
        // move the flag in either direction.
        if (minio == null) return CheckResult.down("minio", "no MinioClient bean");
        try {
            int count = minio.listBuckets().size();
            minioHealthStatus.markUp();
            return CheckResult.up("minio", count + " bucket(s)");
        } catch (Throwable t) {
            if (MinioFailureClassifier.isConnectionLevel(t)) {
                minioHealthStatus.markDegraded("telegram /health probe: " + t.getClass().getSimpleName());
            }
            return CheckResult.down("minio", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Transcode + audit pool saturation. Reports the transcode pool's planned WIDTH alongside its
     * depth, because that width is derived from the host at startup rather than hard-coded — seeing
     * {@code transcode=0/1a w1} on a small box and {@code w7} on a big one is how an operator
     * confirms the capacity planner read the machine correctly.
     */
    CheckResult checkFFmpegQueue() {
        TranscodeExecutor transcode = transcodeExecProvider.getIfAvailable();
        ThreadPoolTaskExecutor audit = auditExecProvider.getIfAvailable();
        if (transcode == null && audit == null) {
            return CheckResult.down("ffmpeg", "no transcode executors");
        }
        var sb = new StringBuilder();
        boolean degraded = false;
        if (transcode != null) {
            sb.append("transcode=").append(transcode.getQueueDepth())
              .append("/").append(transcode.getActiveCount()).append("a")
              .append(" w").append(transcode.getConcurrency()).append(' ');
            degraded = nearCapacity(transcode.getQueueDepth(), transcode.getQueueCapacity());
        }
        if (audit != null) {
            int queue = audit.getThreadPoolExecutor().getQueue().size();
            sb.append("audit=").append(queue).append("/").append(audit.getActiveCount()).append("a");
            degraded |= nearCapacity(queue, audit.getQueueCapacity());
        }
        return degraded
                ? CheckResult.degraded("ffmpeg", sb.toString().trim() + " (queue near capacity)")
                : CheckResult.up("ffmpeg", sb.toString().trim());
    }

    /** Saturation heuristic — degrade past 80% of a pool's queue capacity. */
    private static boolean nearCapacity(int queued, int capacity) {
        return capacity > 0 && queued * 5 >= capacity * 4;
    }

    CheckResult checkDevices() {
        DeviceStatusViewRepository repo = statusViewRepoProvider.getIfAvailable();
        if (repo == null) return CheckResult.down("devices", "no DeviceStatusViewRepository bean");
        try {
            // Zero-fill missing buckets so the row format is stable. Counts come from the
            // heartbeat-derived device_status_view (single source of truth), not the raw,
            // non-authoritative status column — so /health agrees with the dashboard.
            var counts = new LinkedHashMap<Device.Status, Long>();
            for (Device.Status s : Device.Status.values()) counts.put(s, 0L);
            for (Object[] row : repo.countByComputedStatusGrouped()) {
                counts.put((Device.Status) row[0], ((Number) row[1]).longValue());
            }
            String detail = counts.entrySet().stream()
                    .map(e -> e.getKey().name().toLowerCase() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("none");
            return CheckResult.up("devices", detail);
        } catch (Throwable t) {
            return CheckResult.down("devices", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    CheckResult checkIncidents() {
        IncidentRepository repo = incidentRepoProvider.getIfAvailable();
        if (repo == null) return CheckResult.down("incidents", "no IncidentRepository bean");
        try {
            var counts = new LinkedHashMap<Event.Priority, Long>();
            for (Event.Priority p : Event.Priority.values()) counts.put(p, 0L);
            for (Object[] row : repo.countOpenByPriority()) {
                counts.put((Event.Priority) row[0], ((Number) row[1]).longValue());
            }
            long critical = counts.getOrDefault(Event.Priority.CRITICAL, 0L);
            long high = counts.getOrDefault(Event.Priority.HIGH, 0L);
            String detail = counts.entrySet().stream()
                    .map(e -> e.getKey().name().toLowerCase() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("none");
            // Operationally meaningful: any open CRITICAL → DEGRADED so the operator
            // doesn't miss it in a sea of UP rows.
            if (critical > 0) {
                return CheckResult.degraded("incidents", detail + " (CRITICAL open)");
            }
            if (high > 0) {
                return CheckResult.degraded("incidents", detail + " (HIGH open)");
            }
            return CheckResult.up("incidents", detail);
        } catch (Throwable t) {
            return CheckResult.down("incidents", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    CheckResult checkJvmMemory() {
        try {
            long maxBytes = Runtime.getRuntime().maxMemory();
            long totalBytes = Runtime.getRuntime().totalMemory();
            long freeBytes = Runtime.getRuntime().freeMemory();
            long usedBytes = totalBytes - freeBytes;
            int pct = (int) ((usedBytes * 100) / Math.max(1, maxBytes));
            String detail = "used=" + humanBytes(usedBytes) + " / max=" + humanBytes(maxBytes)
                    + " (" + pct + "%)";
            // 90%+ of max is the textbook GC-thrashing zone — degrade so an operator
            // notices BEFORE the OOM hits.
            if (pct >= 90) return CheckResult.degraded("jvm-mem", detail);
            return CheckResult.up("jvm-mem", detail);
        } catch (Throwable t) {
            return CheckResult.down("jvm-mem", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Free space on the shared volume. Shares {@link DiskSpace}'s arithmetic with the
     * {@code disk} component on {@code /api/health}, so an operator comparing the two surfaces is
     * never chasing a discrepancy that only exists because they were computed differently.
     */
    CheckResult checkDisk() {
        try {
            DiskSpace space = DiskSpace.probe(dataVolumePath);
            String detail = space.describe();
            // Below 10% free → degrade. Below 1% → DOWN (an operator is about to have
            // a very bad day).
            if (space.freePercent() < 1) return CheckResult.down("disk", detail);
            if (space.freePercent() < 10) return CheckResult.degraded("disk", detail);
            return CheckResult.up("disk", detail);
        } catch (IOException e) {
            return CheckResult.down("disk", "IOException: " + e.getMessage());
        } catch (Throwable t) {
            return CheckResult.down("disk", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    CheckResult checkRecentErrors() {
        try {
            List<RetainedError> errs = RetainedErrorBuffer.recent(ERROR_WINDOW, ERROR_LIMIT);
            String detail = errs.isEmpty()
                    ? "0 in last " + ERROR_WINDOW.toHours() + "h"
                    : errs.size() + " in last " + ERROR_WINDOW.toHours() + "h";
            return CheckResult.up("errors", detail);
        } catch (Throwable t) {
            return CheckResult.down("errors", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    // ---------- payload rendering ----------

    String renderPayload(List<CheckResult> results) {
        Severity overallSeverity = overallSeverity(results);
        String title = "System health";

        var kv = new LinkedHashMap<String, String>();
        for (CheckResult r : results) {
            kv.put(r.name(), badge(r.status()) + " " + nullSafe(r.detail()));
        }

        var builder = TelegramMessageBuilder.builder()
                .severity(overallSeverity)
                .title(title)
                .kvBlock(kv);

        // Append the recent errors as a separate section so they're scannable independent
        // of the "row count" summary. Section is omitted when no errors recorded.
        List<RetainedError> errors = safeRecent();
        if (!errors.isEmpty()) {
            var sb = new StringBuilder();
            for (RetainedError e : errors) {
                sb.append(formatErrorLine(e)).append('\n');
            }
            // Trim trailing newline; codeBlock adds its own formatting.
            String body = sb.toString().trim();
            builder.codeBlock("Recent errors", body);
        }

        return builder.build();
    }

    private static List<RetainedError> safeRecent() {
        try {
            return RetainedErrorBuffer.recent(ERROR_WINDOW, ERROR_LIMIT);
        } catch (Throwable t) {
            return List.of();
        }
    }

    static String formatErrorLine(RetainedError e) {
        // Compact one-liner — the section header already gives context. Logger name is
        // truncated to its last segment so we don't waste 60 chars on the FQCN.
        String shortLogger = e.logger();
        int dot = shortLogger.lastIndexOf('.');
        if (dot >= 0 && dot < shortLogger.length() - 1) shortLogger = shortLogger.substring(dot + 1);
        String ts = ZonedDateTime.ofInstant(e.timestamp(), UTC_PLUS_5).format(TS);
        String msg = e.message() == null ? "(no message)" : e.message();
        return ts + " " + e.level() + " " + shortLogger + ": " + msg;
    }

    static Severity overallSeverity(List<CheckResult> results) {
        boolean anyDown = false;
        boolean anyDegraded = false;
        for (CheckResult r : results) {
            if (r.status() == CheckStatus.DOWN) anyDown = true;
            else if (r.status() == CheckStatus.DEGRADED) anyDegraded = true;
        }
        if (anyDown) return Severity.ERROR;
        if (anyDegraded) return Severity.WARN;
        return Severity.INFO;
    }

    static String badge(CheckStatus s) {
        return switch (s) {
            case UP -> "UP      ";
            case DEGRADED -> "DEGRADED";
            case DOWN -> "DOWN    ";
        };
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** Delegates to the shared formatter so the JVM-memory and disk rows render identically. */
    static String humanBytes(long bytes) {
        return DiskSpace.humanBytes(bytes);
    }

    /** Map.entry helpers exposed for tests. */
    Map<String, ObjectProvider<?>> dependenciesForTest() {
        var m = new LinkedHashMap<String, ObjectProvider<?>>();
        m.put("dataSource", dataSourceProvider);
        m.put("redis", redisProvider);
        m.put("minio", minioProvider);
        m.put("statusView", statusViewRepoProvider);
        m.put("incidentRepo", incidentRepoProvider);
        m.put("transcodeExec", transcodeExecProvider);
        m.put("auditExec", auditExecProvider);
        return m;
    }

    /** Visible for tests so we can shut down the dedicated pool deterministically. */
    public void shutdown() {
        healthExec.shutdownNow();
    }
}
