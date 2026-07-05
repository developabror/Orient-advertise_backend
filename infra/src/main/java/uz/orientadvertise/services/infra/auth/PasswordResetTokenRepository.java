package uz.orientadvertise.services.infra.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis store for single-use, expiring password-reset tokens. Shapes after
 * {@link RefreshTokenRepository} but is a pure String→String store, so it uses
 * {@link StringRedisTemplate} (the same template {@code LoginRateLimiter} injects).
 *
 * <p><b>Only the SHA-256 hash of the token is stored</b> — the raw token travels solely in
 * the emailed URL. A leaked Redis snapshot therefore can't be replayed into a reset.
 *
 * <p><b>One live link per user.</b> {@link #issue} invalidates the user's previous token
 * before minting a new one. <b>Single-use is atomic:</b> {@link #consume} uses {@code GETDEL}
 * so two concurrent reset POSTs with the same token can't both pass — exactly one wins.
 */
@Repository
public class PasswordResetTokenRepository {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenRepository.class);

    private static final String TOKEN_PREFIX = "pwdreset:tok:";   // pwdreset:tok:{sha256(token)} -> username
    private static final String USER_PREFIX = "pwdreset:user:";   // pwdreset:user:{username}     -> sha256(token)
    private static final int TOKEN_BYTES = 32;                    // 256 bits of entropy

    private final StringRedisTemplate redis;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasswordResetTokenRepository(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Mint a fresh reset token for {@code username}, invalidating any prior one. Stores
     * {@code sha256(token) -> username} and {@code username -> sha256(token)}, both with
     * {@code ttl}. Returns the <b>raw</b> token (the only place it ever exists in cleartext).
     */
    public String issue(String username, Duration ttl) {
        // One live link per user: drop the previous token's hash entry if present.
        String priorHash = redis.opsForValue().get(USER_PREFIX + username);
        if (priorHash != null) {
            redis.delete(TOKEN_PREFIX + priorHash);
        }

        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = sha256Hex(raw);

        redis.opsForValue().set(TOKEN_PREFIX + hash, username, ttl);
        redis.opsForValue().set(USER_PREFIX + username, hash, ttl);
        log.debug("Issued password-reset token for user [{}] (ttl={})", username, ttl);
        return raw;
    }

    /**
     * Atomically consume a token: returns the username and deletes the token in one step
     * ({@code GETDEL}), so it can never be used twice. Also clears the user→hash pointer.
     * Returns empty for an unknown/expired/already-used token.
     */
    public Optional<String> consume(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256Hex(rawToken);
        // GETDEL (Redis >= 6.2) — exactly one concurrent caller can win the race.
        String username = redis.opsForValue().getAndDelete(TOKEN_PREFIX + hash);
        if (username == null) {
            return Optional.empty();
        }
        redis.delete(USER_PREFIX + username);
        return Optional.of(username);
    }

    /** Non-consuming lookup for the validate endpoint — present iff the token is still live. */
    public Optional<String> peekUsername(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(redis.opsForValue().get(TOKEN_PREFIX + sha256Hex(rawToken)));
    }

    /** Full 64-char hex SHA-256 of the token (not a prefix — this is the lookup key). */
    static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS — absence means a broken JVM, not a runtime path.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
