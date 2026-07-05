package uz.orientadvertise.services.service;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SessionCacheService {

    private static final Logger log = LoggerFactory.getLogger(SessionCacheService.class);
    private static final String KEY_PREFIX = "session:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final RedisTemplate<String, Object> redisTemplate;

    public SessionCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void put(String sessionId, Object sessionData) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, sessionData, TTL);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable — session not cached [id={}]: {}", sessionId, e.getMessage());
        }
    }

    public Optional<Object> get(String sessionId) {
        try {
            var value = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
            return Optional.ofNullable(value);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable — session cache miss [id={}]: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    public void evict(String sessionId) {
        try {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        } catch (RedisConnectionFailureException e) {
            log.warn("Redis unavailable — session not evicted [id={}]: {}", sessionId, e.getMessage());
        }
    }
}
