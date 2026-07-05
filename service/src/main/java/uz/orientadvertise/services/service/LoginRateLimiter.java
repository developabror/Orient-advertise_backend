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
 * Throttles {@code /api/auth/login} (and {@code /refresh}) to blunt credential stuffing /
 * brute force. Two independent guards, Redis-backed so they hold across instances:
 * <ul>
 *   <li><b>Per-source-IP attempt rate</b> — fixed window; over the cap → 429.</li>
 *   <li><b>Per-username lockout</b> — after N <em>failed</em> attempts the account is
 *       locked for the window; further attempts → 429 regardless of correct password.</li>
 * </ul>
 * A successful login clears both the failure counter and the lock for that username.
 */
@Service
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    @Value("${app.auth.login-max-attempts-per-window:20}")
    private long maxAttemptsPerWindow;

    @Value("${app.auth.login-max-failures-before-lock:5}")
    private long maxFailuresBeforeLock;

    @Value("${app.auth.login-window-minutes:15}")
    private long windowMinutes;

    private final StringRedisTemplate redis;

    public LoginRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** Throws 429 if the username is locked or the source IP is over its attempt budget. */
    public void checkAllowed(String clientIp, String username) {
        if (username != null && Boolean.TRUE.equals(redis.hasKey("login:lock:" + username))) {
            throw new RateLimitExceededException(
                    "Account temporarily locked after repeated failed logins; try again later");
        }
        incrementAttempt("login:attempt:ip:" + clientIp,
                "Too many login attempts from this source; try again later");
    }

    /** Per-IP rate guard for /refresh (no username dimension). */
    public void checkRefreshAllowed(String clientIp) {
        incrementAttempt("login:refresh:ip:" + clientIp,
                "Too many refresh attempts from this source; try again later");
    }

    /** Record a failed login; lock the account once the failure threshold is crossed. */
    public void recordFailure(String username) {
        if (username == null) {
            return;
        }
        String failKey = "login:fail:" + username;
        Long fails = redis.opsForValue().increment(failKey);
        if (fails == null) {
            return;
        }
        if (fails == 1L) {
            redis.expire(failKey, Duration.ofMinutes(windowMinutes));
        }
        if (fails >= maxFailuresBeforeLock) {
            redis.opsForValue().set("login:lock:" + username, "1", Duration.ofMinutes(windowMinutes));
            log.warn("Account [{}] temporarily locked after {} failed login attempts", username, fails);
        }
    }

    /** Clear the failure counter + lock for a username after a successful login. */
    public void recordSuccess(String username) {
        if (username == null) {
            return;
        }
        redis.delete("login:fail:" + username);
        redis.delete("login:lock:" + username);
    }

    private void incrementAttempt(String keyPrefix, String overLimitMessage) {
        long bucket = Instant.now().getEpochSecond() / (windowMinutes * 60);
        String key = keyPrefix + ":" + bucket;
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            return; // fail open on a Redis hiccup rather than block all logins
        }
        if (count == 1L) {
            redis.expire(key, Duration.ofMinutes(windowMinutes * 2));
        }
        if (count > maxAttemptsPerWindow) {
            throw new RateLimitExceededException(overLimitMessage);
        }
    }
}
