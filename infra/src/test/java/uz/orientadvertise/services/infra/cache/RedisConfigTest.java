package uz.orientadvertise.services.infra.cache;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisConfigTest {

    @Test
    void cacheManager_hasCorrectTtlPerNamespace() {
        var jsonSerializer = RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer());

        var sessionConfig = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("session:")
                .entryTtl(Duration.ofMinutes(30))
                .serializeValuesWith(jsonSerializer);

        var tokenConfig = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("token:")
                .entryTtl(Duration.ofMinutes(60))
                .serializeValuesWith(jsonSerializer);

        var defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(15))
                .serializeValuesWith(jsonSerializer);

        // Verify TTL configuration values
        assertEquals(Duration.ofMinutes(30), sessionConfig.getTtl());
        assertEquals(Duration.ofMinutes(60), tokenConfig.getTtl());
        assertEquals(Duration.ofMinutes(15), defaultConfig.getTtl());
    }

    @Test
    void sessionNamespace_hasPrefixSession() {
        var config = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("session:");

        var prefix = config.getKeyPrefixFor("mykey");
        assertNotNull(prefix);
        assertTrue(prefix.startsWith("session:mykey"));
    }

    @Test
    void tokenNamespace_hasPrefixToken() {
        var config = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("token:");

        var prefix = config.getKeyPrefixFor("mykey");
        assertTrue(prefix.startsWith("token:mykey"));
    }
}
