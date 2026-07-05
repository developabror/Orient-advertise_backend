package uz.orientadvertise.services.infra.telegram;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;

/**
 * Sliding-window rate limiter for outbound Telegram broadcasts.
 *
 * <p><b>Two windows:</b>
 * <ul>
 *   <li><b>Global</b> — at most {@value #GLOBAL_LIMIT} messages per
 *       {@link #GLOBAL_WINDOW} across the whole bot. Catches floods regardless of
 *       message content.</li>
 *   <li><b>Per-message-hash</b> — at most {@value #PER_HASH_LIMIT} identical messages
 *       per {@link #PER_HASH_WINDOW}. Catches a single repeated error spamming the
 *       channel even if the global rate is fine.</li>
 * </ul>
 *
 * <p><b>Severity bypass.</b> {@link Severity#FATAL} skips both windows — these messages
 * are too important to silently drop. Every other severity (and the no-severity
 * overload) counts toward the limits.
 *
 * <p><b>Redis-first, in-memory fallback.</b> Redis sorted sets give us a true sliding
 * window across replicas: each acquire prunes scores older than the window, then
 * counts cardinality, then appends a new entry. If Redis throws (network blip,
 * connection refused), we degrade silently to a per-instance in-memory limiter — same
 * window semantics, but state resets on JVM restart and isn't shared across replicas.
 * Better than failing closed (no messages flow) or failing open (flooding allowed).
 *
 * <p><b>Suppression telemetry.</b> When a request is denied, we increment two Redis
 * hashes ({@code telegram:suppressed:counts}, {@code telegram:suppressed:samples})
 * keyed by message hash so {@link TelegramSuppressedAggregator} can flush a 5-minute
 * summary. The aggregator also re-reads these on a schedule even if Redis is up but
 * the increments failed locally — both layers are best-effort.
 */
public class TelegramRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TelegramRateLimiter.class);

    public static final int GLOBAL_LIMIT = 30;
    public static final Duration GLOBAL_WINDOW = Duration.ofMinutes(1);
    public static final int PER_HASH_LIMIT = 5;
    public static final Duration PER_HASH_WINDOW = Duration.ofMinutes(5);

    static final String KEY_GLOBAL = "telegram:rate:global";
    static final String KEY_MSG_PREFIX = "telegram:rate:msg:";
    static final String KEY_SUPPRESSED_COUNTS = "telegram:suppressed:counts";
    static final String KEY_SUPPRESSED_SAMPLES = "telegram:suppressed:samples";

    private final StringRedisTemplate redis;
    private final InMemoryFallback fallback = new InMemoryFallback();

    public TelegramRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Decision tryAcquire(String message, Severity severity) {
        if (severity == Severity.FATAL) {
            return Decision.bypass();
        }
        String hash = sha256Hex(message);
        long now = System.currentTimeMillis();
        if (redis != null) {
            try {
                Decision redisDecision = tryAcquireRedis(message, hash, now);
                if (redisDecision != null) return redisDecision;
            } catch (Exception e) {
                log.debug("Rate limiter Redis call failed, falling back to in-memory: {}", e.getMessage());
            }
        }
        // Redis is null (test slice without it) or threw — use in-memory fallback.
        return fallback.tryAcquire(message, hash, now);
    }

    /** @return {@link Decision} when Redis answered, or {@code null} when caller must use fallback. */
    private Decision tryAcquireRedis(String message, String hash, long now) {
        var z = redis.opsForZSet();

        // Global window — prune old, count, decide.
        long globalCutoff = now - GLOBAL_WINDOW.toMillis();
        z.removeRangeByScore(KEY_GLOBAL, 0, globalCutoff);
        Long globalCount = z.zCard(KEY_GLOBAL);
        if (globalCount != null && globalCount >= GLOBAL_LIMIT) {
            recordSuppressed(hash, sampleFrom(message));
            return Decision.suppressed();
        }

        // Per-hash window.
        String msgKey = KEY_MSG_PREFIX + hash;
        long msgCutoff = now - PER_HASH_WINDOW.toMillis();
        z.removeRangeByScore(msgKey, 0, msgCutoff);
        Long msgCount = z.zCard(msgKey);
        if (msgCount != null && msgCount >= PER_HASH_LIMIT) {
            recordSuppressed(hash, sampleFrom(message));
            return Decision.suppressed();
        }

        // Both windows have headroom — record the new acquire and allow.
        // Member is timestamp + UUID so concurrent calls in the same millisecond don't
        // collide and lose entries (sorted-set members must be unique).
        String member = now + "-" + UUID.randomUUID();
        z.add(KEY_GLOBAL, member, now);
        redis.expire(KEY_GLOBAL, GLOBAL_WINDOW.plusSeconds(60));
        z.add(msgKey, member, now);
        redis.expire(msgKey, PER_HASH_WINDOW.plusSeconds(60));
        return Decision.allowed();
    }

    private void recordSuppressed(String hash, String sample) {
        try {
            redis.opsForHash().increment(KEY_SUPPRESSED_COUNTS, hash, 1L);
            // putIfAbsent semantics: keep the FIRST sample we saw for this hash so the
            // summary surfaces a stable label instead of churning per-occurrence.
            redis.opsForHash().putIfAbsent(KEY_SUPPRESSED_SAMPLES, hash, sample);
            // Long TTL on both — aggregator flushes every 5 minutes anyway, this is a
            // safety net for stale entries when the aggregator skips a tick.
            redis.expire(KEY_SUPPRESSED_COUNTS, Duration.ofMinutes(15));
            redis.expire(KEY_SUPPRESSED_SAMPLES, Duration.ofMinutes(15));
        } catch (Exception e) {
            log.debug("Could not record suppressed message: {}", e.getMessage());
        }
    }

    // Severity-prefix shape produced by TelegramMessageBuilder:
    //   "🔴 *ERROR* — *Title*"  →  strip the leading emoji + bold label + em-dash so
    // the suppression summary surfaces just the title. We already know the severity
    // because the suppression summary is itself titled "WARN".
    private static final java.util.regex.Pattern SEVERITY_PREFIX =
            java.util.regex.Pattern.compile("^[\\u2B24\\u26AB🔴🟡🟢]\\s*\\*?(INFO|WARN|ERROR|FATAL)\\*?\\s*[—-]\\s*");

    /**
     * First non-empty line of the message — used as the human-readable label in the
     * summary. Strips the severity-emoji prefix and any remaining Markdown emphasis so
     * the operator sees plain text.
     */
    static String sampleFrom(String message) {
        if (message == null) return "(empty)";
        for (String line : message.split("\n", 5)) {
            String trimmed = line.trim();
            trimmed = SEVERITY_PREFIX.matcher(trimmed).replaceFirst("");
            trimmed = trimmed.replaceAll("[*`]", "").trim();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 200 ? trimmed.substring(0, 197) + "…" : trimmed;
            }
        }
        return "(empty)";
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8));
            // First 16 hex chars — enough collision resistance for our purposes,
            // keeps the redis hash field short.
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            // Same JVM-impossible fault as ApiKeyAuthenticationService — a broken JVM, not an
            // operator conflict. Use the config-fault type for a consistent classification.
            throw new IllegalConfigurationException("SHA-256 unavailable", e);
        }
    }

    public enum Outcome { ALLOWED, SUPPRESSED, BYPASS }

    public record Decision(Outcome outcome) {
        public static Decision allowed() { return new Decision(Outcome.ALLOWED); }
        public static Decision suppressed() { return new Decision(Outcome.SUPPRESSED); }
        public static Decision bypass() { return new Decision(Outcome.BYPASS); }
        public boolean shouldSend() { return outcome != Outcome.SUPPRESSED; }
    }

    /**
     * Per-instance sliding-window limiter. Only used when Redis is unreachable —
     * loses state on JVM restart and isn't shared across replicas, but still bounds
     * a single instance's outbound rate so a logspam burst can't flood the chat.
     *
     * <p>Threadsafe via a single global lock — telegram broadcast volume is low and
     * lock contention here is irrelevant compared to the Telegram round-trip itself.
     */
    static final class InMemoryFallback {

        private final ReentrantLock lock = new ReentrantLock();
        private final Deque<Long> globalTimestamps = new ArrayDeque<>();
        private final Map<String, Deque<Long>> perHashTimestamps = new HashMap<>();
        // Suppressed counts/samples tracked locally too — without redis they live only
        // until the aggregator's next flush attempt (which sees no redis state and
        // skips). Operators tail logs to see the in-memory counts via metrics if needed.
        private final Map<String, Long> suppressedCounts = new HashMap<>();
        private final Map<String, String> suppressedSamples = new HashMap<>();

        Decision tryAcquire(String message, String hash, long now) {
            lock.lock();
            try {
                pruneOlderThan(globalTimestamps, now - GLOBAL_WINDOW.toMillis());
                if (globalTimestamps.size() >= GLOBAL_LIMIT) {
                    recordSuppressed(hash, sampleFrom(message));
                    return Decision.suppressed();
                }
                Deque<Long> perHash = perHashTimestamps.computeIfAbsent(hash, k -> new ArrayDeque<>());
                pruneOlderThan(perHash, now - PER_HASH_WINDOW.toMillis());
                if (perHash.size() >= PER_HASH_LIMIT) {
                    recordSuppressed(hash, sampleFrom(message));
                    return Decision.suppressed();
                }
                globalTimestamps.addLast(now);
                perHash.addLast(now);
                return Decision.allowed();
            } finally {
                lock.unlock();
            }
        }

        private void recordSuppressed(String hash, String sample) {
            suppressedCounts.merge(hash, 1L, Long::sum);
            suppressedSamples.putIfAbsent(hash, sample);
        }

        private static void pruneOlderThan(Deque<Long> q, long cutoff) {
            while (!q.isEmpty() && q.peekFirst() <= cutoff) q.pollFirst();
        }

        // Visible for tests / metrics.
        Map<String, Long> drainSuppressedCounts() {
            lock.lock();
            try {
                var out = new HashMap<>(suppressedCounts);
                suppressedCounts.clear();
                return out;
            } finally {
                lock.unlock();
            }
        }
    }

    /** Visible for tests. */
    InMemoryFallback fallback() { return fallback; }
}
