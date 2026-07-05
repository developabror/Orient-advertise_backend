package uz.orientadvertise.services.infra.telegram;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic liveness ping written to Redis so the next startup can detect a hard kill
 * (SIGKILL, OOMKill, hardware crash) — anything that bypasses {@code @PreDestroy}.
 *
 * <p>Pairs with {@link TelegramShutdownNotifier} which writes a separate
 * {@code telegram:last-clean-shutdown} key. On startup, the notifier compares the two:
 * if the heartbeat is more recent than the clean-shutdown timestamp, the previous JVM
 * died unexpectedly — surfaced in the startup notification's "previous run" line.
 *
 * <p>The 30-second cadence is a balance between detection precision and Redis load.
 * Any kill that happens within a 30-second window of the last heartbeat will register
 * as "previous run died ~30s after last seen alive" — close enough.
 *
 * <p>The key carries a 5-minute TTL so a stale heartbeat from a long-dead JVM doesn't
 * corrupt detection — if the previous JVM died and nobody started a new one for 6+
 * minutes, the heartbeat key expires and we treat the next boot as "first run after
 * an outage". The clean-shutdown key has its own 7-day TTL.
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
public class TelegramHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(TelegramHeartbeat.class);

    static final String KEY_LAST_HEARTBEAT = "telegram:last-heartbeat";
    static final Duration HEARTBEAT_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public TelegramHeartbeat(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Scheduled(fixedRateString = "${telegram.bot.heartbeat-rate-ms:30000}")
    public void heartbeat() {
        try {
            redis.opsForValue().set(KEY_LAST_HEARTBEAT,
                    String.valueOf(System.currentTimeMillis()),
                    HEARTBEAT_TTL);
        } catch (Exception e) {
            // Redis hiccup — log at debug. Missing one heartbeat just means the gap
            // analysis has 30s less precision. Not worth raising a warning every tick.
            log.debug("Heartbeat write failed: {}", e.getMessage());
        }
    }
}
