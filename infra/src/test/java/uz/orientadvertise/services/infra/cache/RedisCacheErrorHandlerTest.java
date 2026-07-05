package uz.orientadvertise.services.infra.cache;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.RedisConnectionFailureException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisCacheErrorHandlerTest {

    private final RedisCacheErrorHandler handler = new RedisCacheErrorHandler();
    private final Cache cache = mockCache("test-cache");

    @Test
    void handleCacheGetError_doesNotPropagate() {
        assertDoesNotThrow(() ->
                handler.handleCacheGetError(
                        new RedisConnectionFailureException("Connection refused"),
                        cache, "key1"));
    }

    @Test
    void handleCachePutError_doesNotPropagate() {
        assertDoesNotThrow(() ->
                handler.handleCachePutError(
                        new RedisConnectionFailureException("Connection refused"),
                        cache, "key1", "value1"));
    }

    @Test
    void handleCacheEvictError_doesNotPropagate() {
        assertDoesNotThrow(() ->
                handler.handleCacheEvictError(
                        new RedisConnectionFailureException("Connection refused"),
                        cache, "key1"));
    }

    @Test
    void handleCacheClearError_doesNotPropagate() {
        assertDoesNotThrow(() ->
                handler.handleCacheClearError(
                        new RedisConnectionFailureException("Connection refused"),
                        cache));
    }

    private static Cache mockCache(String name) {
        var cache = mock(Cache.class);
        when(cache.getName()).thenReturn(name);
        return cache;
    }
}
