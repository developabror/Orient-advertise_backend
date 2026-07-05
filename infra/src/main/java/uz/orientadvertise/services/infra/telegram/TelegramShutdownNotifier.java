package uz.orientadvertise.services.infra.telegram;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends a final Telegram message when the application shuts down gracefully.
 *
 * <p><b>Hard 3-second budget.</b> Telegram's API may be slow or unreachable during
 * shutdown — but the JVM is leaving and we must not block context close indefinitely.
 * Every authorized chat is sent to in parallel via {@link CompletableFuture}; the entire
 * batch is awaited with {@code allOf(...).get(3, SECONDS)}. On timeout, in-flight
 * futures are cancelled and we proceed with shutdown — better a missed message than a
 * hung container.
 *
 * <p><b>Hard kill detection.</b> {@code @PreDestroy} doesn't fire on {@code SIGKILL},
 * a JVM crash, or an OOM-killed container. The next startup detects those by comparing
 * the {@code telegram:last-clean-shutdown} key (written here) against the periodic
 * {@code telegram:last-heartbeat} key (written by {@link TelegramHeartbeat}) — if the
 * heartbeat is newer than the last clean shutdown, the previous run died unexpectedly.
 *
 * <p><b>Reason.</b> By definition, if {@code @PreDestroy} runs, this is a graceful
 * shutdown — SIGTERM via Docker stop, programmatic context close, or a clean
 * {@code Ctrl-C}. Distinguishing SIGTERM from a programmatic close is racy across JVM
 * shutdown hooks, so the label stays at {@code "graceful"} unless a shutdown hook flips
 * the flag first (best-effort).
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
public class TelegramShutdownNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramShutdownNotifier.class);

    static final Duration SHUTDOWN_BUDGET = Duration.ofSeconds(3);
    static final String KEY_LAST_CLEAN_SHUTDOWN = "telegram:last-clean-shutdown";
    private static final ZoneId UTC_PLUS_5 = ZoneId.of("Asia/Karachi");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final EnabledTelegramNotifier notifier;
    private final TelegramBotProperties props;
    private final OrientTelegramBot bot;
    private final StringRedisTemplate redis;
    private final AtomicReference<String> reason = new AtomicReference<>("graceful");

    public TelegramShutdownNotifier(EnabledTelegramNotifier notifier,
                                      TelegramBotProperties props,
                                      OrientTelegramBot bot,
                                      StringRedisTemplate redis) {
        this.notifier = notifier;
        this.props = props;
        this.bot = bot;
        this.redis = redis;
    }

    @PostConstruct
    void registerSignalHook() {
        // Best-effort signal detection: when the JVM shutdown hook fires, we know the
        // shutdown was externally signalled (SIGTERM/SIGINT or a `docker stop`), as
        // opposed to a programmatic context.close(). The hook may race with Spring's
        // own shutdown hook — if it loses the race, the @PreDestroy below still reads
        // the default "graceful". Either label is accurate; this just gets us closer.
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                    reason.compareAndSet("graceful", "graceful (SIGTERM/SIGINT)"),
                    "telegram-signal-detector"));
        } catch (IllegalStateException ignored) {
            // JVM already shutting down — happens in some test contexts. Skip.
        }
    }

    @PreDestroy
    public void shutdown() {
        // Step 1: record clean-shutdown timestamp BEFORE any network attempt. Even if
        // the Telegram send hangs and times out, the next start sees the right value.
        recordCleanShutdown();

        // Step 2: bail early on cheap conditions that would just waste the 3s budget.
        if (props.getAuthorizedChatIds().isEmpty()) {
            log.debug("No authorized chat ids — skipping shutdown notification");
            return;
        }
        if (!notifier.isRegistered()) {
            log.info("Telegram bot was not registered — skipping shutdown notification");
            return;
        }

        // Step 3: parallel send across all authorized chats with a single 3s budget.
        String payload = buildPayload();
        ExecutorService exec = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "telegram-shutdown");
            t.setDaemon(true);
            return t;
        });
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        try {
            for (Long chatId : props.getAuthorizedChatIds()) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> bot.send(String.valueOf(chatId), payload, "Markdown"), exec));
            }
            CompletableFuture<Void> all = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
            try {
                all.get(SHUTDOWN_BUDGET.toMillis(), TimeUnit.MILLISECONDS);
                log.info("Shutdown notification sent to {} chat(s)", futures.size());
            } catch (TimeoutException e) {
                long delivered = futures.stream().filter(CompletableFuture::isDone).count();
                log.warn("Shutdown notification exceeded {}s budget — delivered to {}/{} chats, "
                                + "remaining cancelled", SHUTDOWN_BUDGET.toSeconds(),
                        delivered, futures.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Shutdown notification interrupted");
            } catch (Exception e) {
                log.warn("Shutdown notification failed: {}", e.getMessage());
            }
        } finally {
            futures.forEach(f -> f.cancel(true));
            exec.shutdownNow();
        }
    }

    private void recordCleanShutdown() {
        try {
            // 7-day TTL — long enough to detect any reasonable outage gap on next boot,
            // short enough that a stale key doesn't linger forever.
            redis.opsForValue().set(KEY_LAST_CLEAN_SHUTDOWN,
                    String.valueOf(System.currentTimeMillis()),
                    Duration.ofDays(7));
        } catch (Exception e) {
            // Redis already torn down? Log and proceed — gap analysis on the next boot
            // will see "no clean-shutdown record" and label this run as unclean. Worst
            // case, one false-positive "unclean" report.
            log.warn("Could not record clean-shutdown timestamp: {}", e.getMessage());
        }
    }

    String buildPayload() {
        String hostname = resolveHostname();
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        Duration uptime = Duration.ofMillis(uptimeMs);
        String now = ZonedDateTime.now(UTC_PLUS_5).format(TS);

        var kv = new java.util.LinkedHashMap<String, String>();
        kv.put("host", hostname);
        kv.put("uptime", formatUptime(uptime));
        kv.put("reason", reason.get());
        kv.put("timestamp", now);
        return TelegramMessageBuilder.builder()
                .severity(TelegramMessageBuilder.Severity.INFO)
                .title("Application shutting down")
                .kvBlock(kv)
                .build();
    }

    /** Visible for tests. */
    void setReasonForTest(String r) {
        reason.set(r);
    }

    private static String formatUptime(Duration d) {
        long seconds = d.getSeconds();
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return "%dh %dm %ds".formatted(h, m, s);
        if (m > 0) return "%dm %ds".formatted(m, s);
        return "%ds".formatted(s);
    }

    private static String resolveHostname() {
        String envHost = System.getenv("HOSTNAME");
        if (envHost != null && !envHost.isBlank()) return envHost;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
