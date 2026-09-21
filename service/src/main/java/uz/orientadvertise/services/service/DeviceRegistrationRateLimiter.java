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
 * <p>Two budgets. {@link #check} meters registrations of new serials. {@link
 * #checkReregistration} meters attempts on an already-registered serial (AUTH-02: refused with
 * 409 unless an admin opened a window). Keeping them apart means a wiped box retrying every few
 * minutes cannot starve new boxes behind the same venue NAT — and polling a known serial to snatch
 * an admin's window is still metered, not free.
 */
@Service
public class DeviceRegistrationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistrationRateLimiter.class);
    private static final Duration BUCKET_TTL = Duration.ofHours(2);

    @Value("${app.device.register-rate-limit-per-hour:10}")
    private long limitPerHour;

    @Value("${app.device.reregister-rate-limit-per-hour:60}")
    private long reregisterLimitPerHour;

    private final StringRedisTemplate redis;

    public DeviceRegistrationRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Count this registration attempt for the source IP; throws
     * {@link RateLimitExceededException} (→ 429) once the hourly limit is exceeded.
     */
    public void check(String clientIp) {
        count("rate:devreg:", clientIp, limitPerHour, "Device registration");
    }

    /** Same, for an attempt on an already-registered serial — its own, larger budget. */
    public void checkReregistration(String clientIp) {
        count("rate:devrereg:", clientIp, reregisterLimitPerHour, "Device re-registration");
    }

    private void count(String prefix, String clientIp, long limit, String label) {
        long bucket = Instant.now().getEpochSecond() / 3600;
        String redisKey = prefix + (clientIp == null ? "unknown" : clientIp) + ":" + bucket;

        Long count = redis.opsForValue().increment(redisKey);
        if (count == null) {
            // Healthy Redis always returns a count; fail open rather than block registration.
            log.warn("Registration rate-limit increment returned null [ip={}]", clientIp);
            return;
        }
        if (count == 1L) {
            redis.expire(redisKey, BUCKET_TTL);
        }
        if (count > limit) {
            throw new RateLimitExceededException(
                    label + " rate limit exceeded (" + limit + "/hour) for this source");
        }
    }
}
