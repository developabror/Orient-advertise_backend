package uz.orientadvertise.services.infra.auth;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenRepository {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenRepository.class);
    private static final String REFRESH_PREFIX = "refresh:";
    private static final String FAMILY_PREFIX = "family:";
    // userfam:{userId} -> Set<familyId>. Lets a password change/reset kill EVERY session for
    // a user (each login mints a fresh family) without enumerating Redis. Mirrors the
    // per-family set's TTL so a quiet user's index expires alongside its tokens.
    private static final String USER_FAMILIES_PREFIX = "userfam:";
    private static final Duration REFRESH_TTL = Duration.ofDays(7);

    private final RedisTemplate<String, Object> redisTemplate;

    public RefreshTokenRepository(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String store(String userId, String familyId) {
        var tokenId = UUID.randomUUID().toString();
        var tokenData = new RefreshTokenData(tokenId, familyId, userId, System.currentTimeMillis());

        redisTemplate.opsForValue().set(REFRESH_PREFIX + tokenId, tokenData, REFRESH_TTL);
        redisTemplate.opsForSet().add(FAMILY_PREFIX + familyId, tokenId);
        redisTemplate.expire(FAMILY_PREFIX + familyId, REFRESH_TTL);

        // Index the family under its user so a password change can revoke all sessions.
        redisTemplate.opsForSet().add(USER_FAMILIES_PREFIX + userId, familyId);
        redisTemplate.expire(USER_FAMILIES_PREFIX + userId, REFRESH_TTL);

        return tokenId;
    }

    public Optional<RefreshTokenData> find(String tokenId) {
        var data = redisTemplate.opsForValue().get(REFRESH_PREFIX + tokenId);
        if (data instanceof RefreshTokenData rtd) {
            return Optional.of(rtd);
        }
        return Optional.empty();
    }

    public void delete(String tokenId) {
        redisTemplate.delete(REFRESH_PREFIX + tokenId);
    }

    public void invalidateFamily(String familyId) {
        var members = redisTemplate.opsForSet().members(FAMILY_PREFIX + familyId);
        if (members != null) {
            for (var member : members) {
                redisTemplate.delete(REFRESH_PREFIX + member.toString());
            }
        }
        redisTemplate.delete(FAMILY_PREFIX + familyId);
        log.warn("Invalidated token family [familyId={}] — possible reuse attack", familyId);
    }

    public void removeFromFamily(String familyId, String tokenId) {
        redisTemplate.opsForSet().remove(FAMILY_PREFIX + familyId, tokenId);
    }

    /**
     * Revoke <em>every</em> refresh session for a user — used after a password change/reset
     * ("rotate creds → kill all sessions"). Reads the user's family index, invalidates each
     * family, then clears the index. Safe when the user has no recorded families (no-op).
     */
    public void invalidateAllForUser(String userId) {
        var families = redisTemplate.opsForSet().members(USER_FAMILIES_PREFIX + userId);
        int count = 0;
        if (families != null) {
            for (var familyId : families) {
                invalidateFamily(familyId.toString());
                count++;
            }
        }
        redisTemplate.delete(USER_FAMILIES_PREFIX + userId);
        log.info("Revoked all refresh sessions for user [{}] — {} family/families invalidated", userId, count);
    }

    public record RefreshTokenData(String tokenId, String familyId, String userId, long issuedAt) implements java.io.Serializable {
    }
}
