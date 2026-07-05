package uz.orientadvertise.services.infra.telegram;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;

/**
 * Sends a one-time startup message to every authorized chat once the Telegram bot is
 * registered. Triggered by {@link TelegramBotInitializer} after a successful
 * registration.
 *
 * <p><b>Crash-loop protection.</b> A naive notifier would flood the chat during a
 * crash/restart loop. We use a Redis SET-IF-ABSENT with 60-second TTL on the key
 * {@code telegram:startup-cooldown} — at most one startup message per minute, regardless
 * of how many JVM restarts happen in that window. Redis-backed so the cooldown survives
 * the restart itself; an in-memory counter would reset every boot and defeat the
 * protection.
 *
 * <p>Falls back to "skip and log" if Redis is unreachable — a transient Redis hiccup
 * shouldn't cause notification spam either.
 *
 * <p><b>Format.</b> Telegram "Markdown" mode (legacy, simple): bold title, fenced
 * code block for the metadata table.
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
public class TelegramStartupNotifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramStartupNotifier.class);

    private static final String COOLDOWN_KEY = "telegram:startup-cooldown";
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
    private static final ZoneId UTC_PLUS_5 = ZoneId.of("Asia/Karachi");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final EnabledTelegramNotifier notifier;
    private final TelegramBotProperties props;
    private final StringRedisTemplate redis;
    private final Environment environment;
    // BuildProperties is published by Spring Boot's bootBuildInfo task. ObjectProvider
    // tolerates the bean being absent (raw IDE/dev runs without bootBuildInfo) — the
    // version then falls back to "unknown".
    private final ObjectProvider<BuildProperties> buildProperties;

    public TelegramStartupNotifier(EnabledTelegramNotifier notifier,
                                     TelegramBotProperties props,
                                     StringRedisTemplate redis,
                                     Environment environment,
                                     ObjectProvider<BuildProperties> buildProperties) {
        this.notifier = notifier;
        this.props = props;
        this.redis = redis;
        this.environment = environment;
        this.buildProperties = buildProperties;
    }

    public void notifyStartup(ApplicationReadyEvent event) {
        if (props.getAuthorizedChatIds().isEmpty()) {
            log.debug("No authorized chat ids — skipping startup notification");
            return;
        }
        if (!acquireCooldown()) {
            log.info("Startup notification suppressed — within {}s cooldown (likely a restart loop)",
                    COOLDOWN_TTL.toSeconds());
            return;
        }
        String payload = buildPayload(event);
        for (Long chatId : props.getAuthorizedChatIds()) {
            notifier.sendMarkdown(String.valueOf(chatId), payload);
        }
        log.info("Startup notification sent to {} chat(s)", props.getAuthorizedChatIds().size());
    }

    /**
     * Called when bot init failed — we still want operational visibility, so we log the
     * payload at INFO level so a human reading the boot log gets the same metadata they
     * would have seen in Telegram.
     */
    public void notifyStartupLocally(ApplicationReadyEvent event, String reason) {
        log.info("Startup notification not delivered ({}). Intended payload:\n{}",
                reason, buildPayload(event));
    }

    private boolean acquireCooldown() {
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(COOLDOWN_KEY, "1", COOLDOWN_TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // Redis hiccup — be conservative. Skipping is safer than flooding when we
            // can't tell whether a recent restart already notified.
            log.warn("Could not acquire startup-notification cooldown (Redis): {}", e.getMessage());
            return false;
        }
    }

    String buildPayload(ApplicationReadyEvent event) {
        String hostname = resolveHostname();
        String env = resolveActiveProfile();
        long durationSec = event.getTimeTaken() != null
                ? event.getTimeTaken().toSeconds()
                : Duration.ofMillis(System.currentTimeMillis()
                    - java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime()).toSeconds();
        String now = ZonedDateTime.now(UTC_PLUS_5).format(TS);
        String version = resolveVersion();
        String previous = resolvePreviousShutdown();

        var kv = new LinkedHashMap<String, String>();
        kv.put("host", hostname);
        kv.put("version", version);
        kv.put("environment", env);
        kv.put("duration", durationSec + "s");
        kv.put("previous", previous);
        kv.put("timestamp", now);
        return TelegramMessageBuilder.builder()
                .severity(Severity.INFO)
                .title("Application started")
                .kvBlock(kv)
                .build();
    }

    private String resolveVersion() {
        var bp = buildProperties.getIfAvailable();
        return bp != null ? bp.getVersion() : "unknown";
    }

    /**
     * Gap analysis. Cross-references {@code telegram:last-clean-shutdown} (set by
     * {@link TelegramShutdownNotifier#shutdown}) and {@code telegram:last-heartbeat}
     * (set every 30s by {@link TelegramHeartbeat}).
     *
     * <ul>
     *   <li><b>Both keys missing</b> — first run, no prior JVM detected.</li>
     *   <li><b>Heartbeat present, clean-shutdown missing or older</b> — previous JVM died
     *       without firing {@code @PreDestroy}. Almost certainly a SIGKILL, OOMKill,
     *       or hard crash.</li>
     *   <li><b>Clean-shutdown newer than heartbeat</b> — graceful previous run.</li>
     * </ul>
     */
    private String resolvePreviousShutdown() {
        try {
            String lastHeartbeat = redis.opsForValue().get("telegram:last-heartbeat");
            String lastClean = redis.opsForValue().get("telegram:last-clean-shutdown");
            if (lastHeartbeat == null && lastClean == null) {
                return "first run";
            }
            long hb = lastHeartbeat == null ? 0L : Long.parseLong(lastHeartbeat);
            long cs = lastClean == null ? 0L : Long.parseLong(lastClean);
            if (cs >= hb) {
                long agoSec = (System.currentTimeMillis() - cs) / 1000;
                return "clean (" + agoSec + "s ago)";
            }
            // Heartbeat is more recent than clean-shutdown — previous run died unexpectedly.
            long agoSec = (System.currentTimeMillis() - hb) / 1000;
            return "UNCLEAN (last seen " + agoSec + "s ago — likely SIGKILL/OOM)";
        } catch (NumberFormatException | IllegalStateException e) {
            return "unknown";
        } catch (Exception e) {
            log.debug("Could not read previous-shutdown markers: {}", e.getMessage());
            return "unknown";
        }
    }

    private static String resolveHostname() {
        // Prefer container/pod hostname env, fall back to InetAddress, then "unknown".
        String envHost = System.getenv("HOSTNAME");
        if (envHost != null && !envHost.isBlank()) return envHost;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }

    private String resolveActiveProfile() {
        var active = environment.getActiveProfiles();
        if (active.length > 0) return active[0];
        var def = environment.getDefaultProfiles();
        return def.length > 0 ? def[0] : "default";
    }
}
