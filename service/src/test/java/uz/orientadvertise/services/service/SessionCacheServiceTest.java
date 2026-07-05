package uz.orientadvertise.services.service;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionCacheServiceTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOps;
    private SessionCacheService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new SessionCacheService(redisTemplate);
    }

    @Test
    void put_storesWithSessionPrefixAndTtl() {
        service.put("abc123", "session-data");
        verify(valueOps).set(eq("session:abc123"), eq("session-data"), eq(Duration.ofMinutes(30)));
    }

    @Test
    void get_returnsValueWhenPresent() {
        when(valueOps.get("session:abc123")).thenReturn("session-data");
        var result = service.get("abc123");
        assertEquals(Optional.of("session-data"), result);
    }

    @Test
    void get_returnsEmptyWhenMissing() {
        when(valueOps.get("session:missing")).thenReturn(null);
        var result = service.get("missing");
        assertTrue(result.isEmpty());
    }

    @Test
    void get_returnsEmptyOnConnectionFailure() {
        when(valueOps.get("session:abc123"))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));
        var result = service.get("abc123");
        assertTrue(result.isEmpty());
    }

    @Test
    void put_doesNotThrowOnConnectionFailure() {
        doThrow(new RedisConnectionFailureException("Connection refused"))
                .when(valueOps).set(any(), any(), any(Duration.class));
        service.put("abc123", "data");
        // No exception — graceful degradation
    }

    @Test
    void evict_deletesKey() {
        service.evict("abc123");
        verify(redisTemplate).delete("session:abc123");
    }

    @Test
    void evict_doesNotThrowOnConnectionFailure() {
        when(redisTemplate.delete("session:abc123"))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));
        service.evict("abc123");
        // No exception
    }
}
