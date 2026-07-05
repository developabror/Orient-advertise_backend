package uz.orientadvertise.services.infra.cache;

import java.time.Duration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.Map;

@Configuration
@EnableCaching
public class RedisConfig implements CachingConfigurer {

    public static final String CACHE_SESSION = "session";
    public static final String CACHE_TOKEN = "token";
    public static final String CACHE_DIAGNOSTICS = "diagnostics";
    public static final String CACHE_DASHBOARD = "dashboard";

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.timeout:2000}")
    private long timeoutMs;

    @Value("${app.cache.session-ttl-minutes:30}")
    private long sessionTtlMinutes;

    @Value("${app.cache.token-ttl-minutes:60}")
    private long tokenTtlMinutes;

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        var serverConfig = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            serverConfig.setPassword(password);
        }

        var socketOptions = SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();

        var clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();

        var clientConfig = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofMillis(timeoutMs))
                .build();

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        var template = new RedisTemplate<String, Object>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    /**
     * Plain string-keyed/string-valued template — needed by the API-key rate limiter,
     * which calls {@code INCR} against a counter. The default JSON value serializer on
     * the generic template stores values as quoted JSON, which {@code INCR} can't parse,
     * so a separate template with raw string serialization is required.
     */
    @Bean
    public org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate(
            RedisConnectionFactory connectionFactory) {
        return new org.springframework.data.redis.core.StringRedisTemplate(connectionFactory);
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        var jsonSerializer = RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer());

        var sessionConfig = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("session:")
                .entryTtl(Duration.ofMinutes(sessionTtlMinutes))
                .serializeValuesWith(jsonSerializer)
                .disableCachingNullValues();

        var tokenConfig = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("token:")
                .entryTtl(Duration.ofMinutes(tokenTtlMinutes))
                .serializeValuesWith(jsonSerializer)
                .disableCachingNullValues();

        var diagnosticsConfig = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("diag:")
                .entryTtl(Duration.ofSeconds(30))
                .serializeValuesWith(jsonSerializer)
                .disableCachingNullValues();

        // Dashboard summary backing FE-11/FE-12. 30s TTL is short enough that a status
        // flip propagates without operator confusion, but long enough that the page
        // refresh patterns of a 10-user ops team don't hammer the aggregation queries.
        // The DeviceHealthMonitor job evicts on demand so the cache catches up to a
        // status sweep faster than the TTL would permit on its own.
        var dashboardConfig = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("dashboard:")
                .entryTtl(Duration.ofSeconds(30))
                .serializeValuesWith(jsonSerializer)
                .disableCachingNullValues();

        var defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(15))
                .serializeValuesWith(jsonSerializer)
                .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(Map.of(
                        CACHE_SESSION, sessionConfig,
                        CACHE_TOKEN, tokenConfig,
                        CACHE_DIAGNOSTICS, diagnosticsConfig,
                        CACHE_DASHBOARD, dashboardConfig
                ))
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new RedisCacheErrorHandler();
    }
}
