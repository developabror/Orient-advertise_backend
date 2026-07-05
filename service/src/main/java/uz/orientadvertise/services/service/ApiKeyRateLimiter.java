package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-key fixed-window rate limiter (100 requests per hour).
 *
 * <p>Backed by Redis so the limit is enforced across multiple application instances.
 * Each key has a single counter in the form {@code rate:apikey:{id}:{epochHour}}; we
 * {@code INCR} on each request and {@link #BUCKET_TTL set TTL} on first creation. A new
 * key naturally takes effect at the next hour boundary because the bucket's name
 * changes — no manual reset needed.
 *
 * <p>The reset timestamp returned in {@code X-RateLimit-Reset} is the start of the next
 * hour bucket — clients can compute backoff cleanly without parsing a header.
 *
 * <p>Counter increments first, then we compare to the limit. A request that pushes the
 * count past the limit is rejected, but it still consumed a token — which is
 * intentional: the rate limit is about <em>attempts</em>, not just successful calls,
 * matching how API gateways elsewhere behave.
 */
@Service
public class ApiKeyRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRateLimiter.class);

    public static final long LIMIT_PER_HOUR = 100L;
    public static final Duration BUCKET_TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;

    public ApiKeyRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Decision tryAcquire(Long apiKeyId) {
        long nowEpochSec = Instant.now().getEpochSecond();
        long bucket = nowEpochSec / 3600;
        long resetEpochSec = (bucket + 1) * 3600;
        String redisKey = "rate:apikey:" + apiKeyId + ":" + bucket;

        Long count = redis.opsForValue().increment(redisKey);
        if (count == null) {
            // Should not happen with a healthy Redis; fail open with conservative remaining.
            log.warn("Rate-limit increment returned null [keyId={}]", apiKeyId);
            return new Decision(true, LIMIT_PER_HOUR, LIMIT_PER_HOUR - 1, resetEpochSec);
        }
        if (count == 1L) {
            // First hit in this bucket — set TTL so stale buckets don't accumulate.
            // 2-hour TTL covers clock skew across replicas and the bucket's natural span.
            redis.expire(redisKey, BUCKET_TTL);
        }

        long remaining = Math.max(0L, LIMIT_PER_HOUR - count);
        boolean allowed = count <= LIMIT_PER_HOUR;
        return new Decision(allowed, LIMIT_PER_HOUR, remaining, resetEpochSec);
    }

    public record Decision(boolean allowed, long limit, long remaining, long resetEpochSeconds) {}
}
