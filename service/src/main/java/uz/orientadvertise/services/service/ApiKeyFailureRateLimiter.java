package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-source-IP fixed-window limiter for <em>failed</em> {@code X-API-Key} authentications.
 * The per-key limiter ({@link ApiKeyRateLimiter}) only runs once a key has been resolved, so
 * it can't throttle someone guessing keys; this caps invalid-key attempts per IP so the 401
 * path can't be used to brute-force the key space.
 */
@Service
public class ApiKeyFailureRateLimiter {

    private static final Duration BUCKET_TTL = Duration.ofHours(2);

    @Value("${app.apikey.failed-auth-limit-per-hour:30}")
    private long limitPerHour;

    private final StringRedisTemplate redis;

    public ApiKeyFailureRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return {@code true} if this failed-auth attempt from the IP is within the limit. */
    public boolean allowFailure(String clientIp) {
        long bucket = Instant.now().getEpochSecond() / 3600;
        String key = "rate:apikeyfail:" + (clientIp == null ? "unknown" : clientIp) + ":" + bucket;
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            return true; // fail open on a Redis hiccup
        }
        if (count == 1L) {
            redis.expire(key, BUCKET_TTL);
        }
        return count <= limitPerHour;
    }
}
