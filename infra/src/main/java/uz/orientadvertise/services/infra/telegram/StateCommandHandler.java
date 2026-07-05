package uz.orientadvertise.services.infra.telegram;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;

/**
 * Handles {@code /state} — a quick liveness + identity probe an operator can fire from
 * an authorized chat to confirm the bot's owning JVM is healthy.
 *
 * <p><b>Response shape.</b>
 * <pre>
 * 🟢 *INFO* — *Server is alive*
 * ```
 * host      : service-prod-7
 * uptime    : 3d 14h 22m
 * version   : 1.0.66
 * timestamp : 2026-05-07 14:32:11 +0500
 * ```
 * </pre>
 *
 * <p><b>Latency budget.</b> The handler must reply within 1 second. Each metric is
 * gathered on the polling thread (no async hop) — they're all in-process reads
 * (RuntimeMXBean, hostname resolution, BuildProperties bean lookup, clock) so the
 * gather phase is sub-millisecond. The Telegram API round-trip is the only network call
 * and typically completes in 100–300ms — comfortably inside the budget. Sends bypass
 * the outbound queue (whose 5s per-send timeout is incompatible with sub-1s response).
 *
 * <p><b>Failure isolation.</b> Each metric is wrapped in its own {@link #safeGet} —
 * one failing metric ({@link UnknownHostException}, missing BuildProperties bean, etc.)
 * collapses to a {@code "—"} placeholder and the report still goes out. The whole
 * {@link #handle} body is additionally wrapped in a top-level {@code try / catch
 * (Throwable)} so even an unexpected payload-build bug results in a fallback plain-text
 * "alive" reply rather than a swallowed silence — the spec demands the response always
 * succeed.
 *
 * <p><b>Authorization.</b> The dispatcher only invokes this handler for chat ids that
 * were already verified by {@link OrientTelegramBot#onUpdateReceived}. No re-check.
 *
 * <p><b>Timestamp zone.</b> UTC+5 per spec — fixed via {@code Asia/Karachi} which has
 * been on +05:00 with no DST since 1971, matching what the rest of the bot subsystem
 * uses (see {@link TelegramShutdownNotifier}).
 */
public class StateCommandHandler implements TelegramCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(StateCommandHandler.class);

    static final String COMMAND = "/state";
    static final ZoneId UTC_PLUS_5 = ZoneId.of("Asia/Karachi");
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxxx");
    static final String FALLBACK = "—";

    private final OrientTelegramBot bot;
    private final ObjectProvider<BuildProperties> buildProvider;

    public StateCommandHandler(OrientTelegramBot bot,
                                 ObjectProvider<BuildProperties> buildProvider) {
        this.bot = bot;
        this.buildProvider = buildProvider;
    }

    @Override
    public String name() {
        return COMMAND;
    }

    @Override
    public void handle(Long chatId, String[] args) {
        // Outer guard: even if payload assembly throws unexpectedly, the user must get
        // SOMETHING. The spec's "command response must always succeed" requirement is
        // load-bearing — silence after a /state is worse than a degraded reply.
        try {
            String payload = buildPayload();
            bot.send(String.valueOf(chatId), payload, "Markdown");
        } catch (Throwable t) {
            log.warn("/state payload build failed unexpectedly: {}", t.toString(), t);
            try {
                bot.send(String.valueOf(chatId),
                        "🟢 Server is alive (state details unavailable)");
            } catch (Throwable ignored) {
                // Nothing more we can do — don't propagate. bot.send already swallows
                // TelegramApiException; only an unchecked from inside the SendMessage
                // builder could land here. Logging the inner failure has no value.
            }
        }
    }

    /** Visible for tests. */
    String buildPayload() {
        var kv = new LinkedHashMap<String, String>();
        kv.put("host", safeGet(this::hostname));
        kv.put("uptime", safeGet(this::uptime));
        kv.put("version", safeGet(this::version));
        kv.put("timestamp", safeGet(this::timestamp));

        return TelegramMessageBuilder.builder()
                .severity(Severity.INFO)
                .title("Server is alive")
                .kvBlock(kv)
                .build();
    }

    /**
     * Run a metric supplier with a per-metric guard. A failure here is intentionally
     * isolated to a single row of the report — the spec calls out that one failed metric
     * must not break the entire response.
     */
    static String safeGet(Supplier<String> supplier) {
        try {
            String v = supplier.get();
            return v == null || v.isBlank() ? FALLBACK : v;
        } catch (Throwable t) {
            log.debug("/state metric collection failed: {}", t.toString());
            return FALLBACK;
        }
    }

    String hostname() {
        // Prefer the container env var — InetAddress.getLocalHost can hang briefly on
        // misconfigured DNS, which would eat into the 1s budget.
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) return env;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    String uptime() {
        long ms = ManagementFactory.getRuntimeMXBean().getUptime();
        return formatUptime(Duration.ofMillis(ms));
    }

    String version() {
        BuildProperties bp = buildProvider.getIfAvailable();
        return bp == null ? "unknown" : bp.getVersion();
    }

    String timestamp() {
        return ZonedDateTime.now(UTC_PLUS_5).format(TS);
    }

    /**
     * Spec format: {@code 3d 14h 22m}. Days resolution is enough for an alive check —
     * second-resolution would mostly add noise to the very common multi-day uptimes.
     * Sub-minute uptimes ({@code 3s}) and sub-day uptimes ({@code 4h 12m}) gracefully
     * shorten to the largest non-zero unit pair.
     */
    static String formatUptime(Duration d) {
        long seconds = d.getSeconds();
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (days > 0) return "%dd %dh %dm".formatted(days, hours, minutes);
        if (hours > 0) return "%dh %dm".formatted(hours, minutes);
        if (minutes > 0) return "%dm %ds".formatted(minutes, secs);
        return "%ds".formatted(secs);
    }
}
