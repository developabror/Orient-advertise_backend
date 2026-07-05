package uz.orientadvertise.services.service;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenCacheServiceTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ValueOperations<String, Object> valueOps;
    private TokenCacheService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new TokenCacheService(redisTemplate);
    }

    @Test
    void put_storesWithTokenPrefixAndTtl() {
        service.put("tok-xyz", "token-data");
        verify(valueOps).set(eq("token:tok-xyz"), eq("token-data"), eq(Duration.ofMinutes(60)));
    }

    @Test
    void get_returnsValueWhenPresent() {
        when(valueOps.get("token:tok-xyz")).thenReturn("token-data");
        var result = service.get("tok-xyz");
        assertEquals(Optional.of("token-data"), result);
    }

    @Test
    void get_returnsEmptyOnConnectionFailure() {
        when(valueOps.get("token:tok-xyz"))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));
        var result = service.get("tok-xyz");
        assertTrue(result.isEmpty());
    }

    @Test
    void exists_returnsTrueWhenKeyPresent() {
        when(redisTemplate.hasKey("token:tok-xyz")).thenReturn(true);
        assertTrue(service.exists("tok-xyz"));
    }

    @Test
    void exists_returnsFalseOnConnectionFailure() {
        when(redisTemplate.hasKey("token:tok-xyz"))
                .thenThrow(new RedisConnectionFailureException("Connection refused"));
        assertFalse(service.exists("tok-xyz"));
    }

    @Test
    void evict_deletesKey() {
        service.evict("tok-xyz");
        verify(redisTemplate).delete("token:tok-xyz");
    }

    @Test
    void put_doesNotThrowOnConnectionFailure() {
        doThrow(new RedisConnectionFailureException("Connection refused"))
                .when(valueOps).set(any(), any(), any(Duration.class));
        service.put("tok-xyz", "data");
        // No exception
    }
}
