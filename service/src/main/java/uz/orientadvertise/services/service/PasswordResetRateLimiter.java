package uz.orientadvertise.services.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import uz.orientadvertise.services.common.exception.RateLimitExceededException;

/**
 * Redis fixed-window throttle for the password-reset surface — copies
 * {@link LoginRateLimiter}'s shape. Two guards, both {@link RateLimitExceededException} (429):
 *
 * <ul>
 *   <li>{@link #checkForgotAllowed} — per-IP <b>and</b> per-email cap so a single address
 *       can't be bombed. <b>Address-blind:</b> the per-email bucket keys on
 *       {@code sha256(email)} and both guards throw the <em>identical</em> message, so a 429
 *       can't be used to probe which emails exist.</li>
 *   <li>{@link #checkResetAllowed} — per-IP cap on reset attempts (defence-in-depth against
 *       token brute-force; the token is 256-bit so guessing is already infeasible).</li>
 * </ul>
 *
 * <p><b>Fail-open</b> on any Redis hiccup — a cache outage must not lock everyone out of
 * recovery.
 */
@Service
public class PasswordResetRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetRateLimiter.class);
    // Single message for BOTH forgot guards — a distinguishable "this email is limited"
    // response would itself be an enumeration side channel.
    private static final String FORGOT_OVER_LIMIT = "Too many password-reset requests; please try again later.";
    private static final String RESET_OVER_LIMIT = "Too many password-reset attempts; please try again later.";

    // Field initializers double as the unit-test defaults (no Spring → @Value not injected).
    @Value("${app.auth.forgot-max-per-window:5}")
    private long maxPerIp = 5;

    @Value("${app.auth.forgot-max-per-email:3}")
    private long maxPerEmail = 3;

    @Value("${app.auth.forgot-window-minutes:15}")
    private long windowMinutes = 15;

    @Value("${app.auth.reset-max-per-window:10}")
    private long maxResetPerIp = 10;

    private final StringRedisTemplate redis;

    public PasswordResetRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Throws 429 if the source IP or the target email is over its forgot-password budget. */
    public void checkForgotAllowed(String clientIp, String email) {
        incrementAttempt("pwdreset:forgot:ip:" + clientIp, maxPerIp, FORGOT_OVER_LIMIT);
        // Hash the (normalized) email so the bucket key never echoes the address.
        incrementAttempt("pwdreset:forgot:email:" + sha256Hex(normalize(email)), maxPerEmail, FORGOT_OVER_LIMIT);
    }

    /** Throws 429 if the source IP is over its reset-attempt budget. */
    public void checkResetAllowed(String clientIp) {
        incrementAttempt("pwdreset:reset:ip:" + clientIp, maxResetPerIp, RESET_OVER_LIMIT);
    }

    private void incrementAttempt(String keyPrefix, long max, String overLimitMessage) {
        long bucket = Instant.now().getEpochSecond() / (windowMinutes * 60);
        String key = keyPrefix + ":" + bucket;
        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofMinutes(windowMinutes * 2));
            }
        } catch (Exception e) {
            // Fail open — a Redis blip must not block password recovery for everyone.
            log.debug("Reset rate limiter Redis hiccup, failing open: {}", e.getMessage());
            return;
        }
        if (count == null) {
            return;
        }
        if (count > max) {
            throw new RateLimitExceededException(overLimitMessage);
        }
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String sha256Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
