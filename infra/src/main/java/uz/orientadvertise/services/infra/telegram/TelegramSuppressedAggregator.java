package uz.orientadvertise.services.infra.telegram;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;

/**
 * Periodically flushes a Telegram-rate-limit summary message: total suppressed count
 * over the last 5 minutes plus the top offending hash with its first-line sample and
 * occurrence count.
 *
 * <p>Reads from the two Redis hashes that {@link TelegramRateLimiter} populates on
 * every suppressed broadcast — {@link TelegramRateLimiter#KEY_SUPPRESSED_COUNTS} and
 * {@link TelegramRateLimiter#KEY_SUPPRESSED_SAMPLES} — builds a Markdown payload, and
 * dispatches it directly through {@link OrientTelegramBot#send} (bypassing the
 * notifier's own rate limiter to avoid recursion).
 *
 * <p>Cadence is set to 5 minutes to match {@link TelegramRateLimiter#PER_HASH_WINDOW}
 * — the summary covers the same window the limiter is policing.
 *
 * <p><b>Redis-only.</b> The in-memory fallback in {@code TelegramRateLimiter} keeps
 * suppression counts locally too, but those don't reach this aggregator (no shared
 * state). When Redis is the failing component, summary messages are skipped — the
 * operator sees the in-memory counters via standard log/metrics rather than Telegram.
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
public class TelegramSuppressedAggregator {

    private static final Logger log = LoggerFactory.getLogger(TelegramSuppressedAggregator.class);

    static final String FIXED_DELAY = "${telegram.bot.suppressed-flush-ms:300000}"; // 5 min default
    static final String INITIAL_DELAY = "${telegram.bot.suppressed-flush-initial-ms:300000}";

    private final StringRedisTemplate redis;
    private final OrientTelegramBot bot;
    private final TelegramBotProperties props;
    private final EnabledTelegramNotifier notifier;

    public TelegramSuppressedAggregator(StringRedisTemplate redis,
                                          OrientTelegramBot bot,
                                          TelegramBotProperties props,
                                          EnabledTelegramNotifier notifier) {
        this.redis = redis;
        this.bot = bot;
        this.props = props;
        this.notifier = notifier;
    }

    @Scheduled(fixedDelayString = FIXED_DELAY, initialDelayString = INITIAL_DELAY)
    public void flushSummary() {
        if (!notifier.isRegistered()) return;
        if (props.getAuthorizedChatIds().isEmpty()) {
            // Drain anyway so counters don't grow stale forever.
            tryDelete();
            return;
        }

        Map<String, Long> counts;
        Map<String, String> samples;
        try {
            counts = readCountsAndDelete();
            samples = readSamplesAndDelete();
        } catch (Exception e) {
            log.debug("Could not read suppressed-counts hash: {}", e.getMessage());
            return;
        }
        if (counts.isEmpty()) return;

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        var top = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElseThrow();
        String topSample = samples.getOrDefault(top.getKey(), "(unknown)");

        String windowMin = String.valueOf(TelegramRateLimiter.PER_HASH_WINDOW.toMinutes());
        String payload = TelegramMessageBuilder.builder()
                .severity(Severity.WARN)
                .title("Suppressed messages summary")
                .text(total + " messages suppressed in last " + windowMin + " minutes")
                .text("Top: " + topSample + " (" + top.getValue() + " occurrences)")
                .build();

        // Bypass the notifier's broadcastMarkdown (which goes through the rate limiter)
        // — this summary is itself a rate-limit artefact and must not be suppressed.
        for (Long chatId : props.getAuthorizedChatIds()) {
            try {
                bot.send(String.valueOf(chatId), payload, "Markdown");
            } catch (Throwable t) {
                log.debug("Suppressed-summary send failed [chatId={}]: {}", chatId, t.getMessage());
            }
        }
        log.info("Suppressed-messages summary flushed: {} suppressed across {} unique messages",
                total, counts.size());
    }

    private Map<String, Long> readCountsAndDelete() {
        var raw = redis.opsForHash().entries(TelegramRateLimiter.KEY_SUPPRESSED_COUNTS);
        Map<String, Long> result = new HashMap<>();
        for (var e : raw.entrySet()) {
            try {
                result.put(String.valueOf(e.getKey()), Long.parseLong(String.valueOf(e.getValue())));
            } catch (NumberFormatException ignored) {
                // Stale/corrupt entry — skip rather than crash the flush.
            }
        }
        redis.delete(TelegramRateLimiter.KEY_SUPPRESSED_COUNTS);
        return result;
    }

    private Map<String, String> readSamplesAndDelete() {
        var raw = redis.opsForHash().entries(TelegramRateLimiter.KEY_SUPPRESSED_SAMPLES);
        Map<String, String> result = raw.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> String.valueOf(e.getKey()),
                        e -> String.valueOf(e.getValue()),
                        (a, b) -> a));
        redis.delete(TelegramRateLimiter.KEY_SUPPRESSED_SAMPLES);
        return result;
    }

    private void tryDelete() {
        try {
            redis.delete(TelegramRateLimiter.KEY_SUPPRESSED_COUNTS);
            redis.delete(TelegramRateLimiter.KEY_SUPPRESSED_SAMPLES);
        } catch (Exception ignored) {
            // Best-effort cleanup.
        }
    }
}
