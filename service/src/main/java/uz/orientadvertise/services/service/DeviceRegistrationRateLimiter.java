package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import uz.orientadvertise.services.common.exception.RateLimitExceededException;

/**
 * Per-source-IP fixed-window limiter for {@code POST /api/devices/register} — the one
 * device endpoint that must stay reachable without a token (first contact). Blunts
 * serial-number guessing / mass-registration abuse. Redis-backed (mirrors
 * {@link ApiKeyRateLimiter}) so the limit holds across instances.
 *
 * <p>Defense-in-depth only: it does not replace the deeper hardening (a provisioning
 * secret + proof-of-possession before token rotation on re-registration) tracked as a
 * follow-up.
 */
@Service
public class DeviceRegistrationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistrationRateLimiter.class);
    private static final Duration BUCKET_TTL = Duration.ofHours(2);

    @Value("${app.device.register-rate-limit-per-hour:10}")
    private long limitPerHour;

    private final StringRedisTemplate redis;

    public DeviceRegistrationRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Count this registration attempt for the source IP; throws
     * {@link RateLimitExceededException} (→ 429) once the hourly limit is exceeded.
     */
    public void check(String clientIp) {
        long bucket = Instant.now().getEpochSecond() / 3600;
        String redisKey = "rate:devreg:" + (clientIp == null ? "unknown" : clientIp) + ":" + bucket;

        Long count = redis.opsForValue().increment(redisKey);
        if (count == null) {
            // Healthy Redis always returns a count; fail open rather than block registration.
            log.warn("Registration rate-limit increment returned null [ip={}]", clientIp);
            return;
        }
        if (count == 1L) {
            redis.expire(redisKey, BUCKET_TTL);
        }
        if (count > limitPerHour) {
            throw new RateLimitExceededException(
                    "Device registration rate limit exceeded (" + limitPerHour + "/hour) for this source");
        }
    }
}
