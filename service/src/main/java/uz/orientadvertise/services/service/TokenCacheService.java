package uz.orientadvertise.services.service;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class    TokenCacheService {

    private static final Logger log = LoggerFactory.getLogger(TokenCacheService.class);
    private static final String KEY_PREFIX = "token:";
    private static final Duration TTL = Duration.ofMinutes(60);

    private final RedisTemplate<String, Object> redisTemplate;

    public TokenCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void put(String tokenId, Object tokenData) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + tokenId, tokenData, TTL);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable — token not cached [id={}]: {}", tokenId, e.getMessage());
        }
    }

    public Optional<Object> get(String tokenId) {
        try {
            var value = redisTemplate.opsForValue().get(KEY_PREFIX + tokenId);
            return Optional.ofNullable(value);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable — token cache miss [id={}]: {}", tokenId, e.getMessage());
            return Optional.empty();
        }
    }

    public void evict(String tokenId) {
        try {
            redisTemplate.delete(KEY_PREFIX + tokenId);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable — token not evicted [id={}]: {}", tokenId, e.getMessage());
        }
    }

    public boolean exists(String tokenId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + tokenId));
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable — token existence check failed [id={}]: {}", tokenId, e.getMessage());
            return false;
        }
    }
}
