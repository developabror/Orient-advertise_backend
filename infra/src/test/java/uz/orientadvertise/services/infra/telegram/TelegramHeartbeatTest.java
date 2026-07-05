package uz.orientadvertise.services.infra.telegram;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramHeartbeatTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private TelegramHeartbeat heartbeat;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> typedOps = mock(ValueOperations.class);
        ops = typedOps;
        when(redis.opsForValue()).thenReturn(ops);
        heartbeat = new TelegramHeartbeat(redis);
    }

    @Test
    void heartbeat_writesKeyWithTtl() {
        heartbeat.heartbeat();

        verify(ops, times(1)).set(eq(TelegramHeartbeat.KEY_LAST_HEARTBEAT),
                anyString(), eq(TelegramHeartbeat.HEARTBEAT_TTL));
    }

    @Test
    void heartbeat_redisFailure_silentlySwallowed() {
        // A transient Redis hiccup must not propagate — losing one heartbeat just means
        // 30s less precision in the next gap analysis. Not worth a stack trace per tick.
        doThrow(new RuntimeException("redis down"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        assertDoesNotThrow(() -> heartbeat.heartbeat());
    }
}
