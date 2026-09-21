package uz.orientadvertise.services.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import uz.orientadvertise.services.common.exception.RateLimitExceededException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceRegistrationRateLimiterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private DeviceRegistrationRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        limiter = new DeviceRegistrationRateLimiter(redis);
        // Field-injected @Value in production; unset (0) in a plain unit test.
        ReflectionTestUtils.setField(limiter, "limitPerHour", 10L);
        ReflectionTestUtils.setField(limiter, "reregisterLimitPerHour", 60L);
    }

    @Test
    void newAndReregistrationAttempts_countInSeparateBuckets() {
        when(ops.increment(anyString())).thenReturn(1L);

        limiter.check("203.0.113.7");
        limiter.checkReregistration("203.0.113.7");

        var keys = ArgumentCaptor.forClass(String.class);
        verify(ops, org.mockito.Mockito.times(2)).increment(keys.capture());
        assertTrue(keys.getAllValues().get(0).startsWith("rate:devreg:203.0.113.7:"));
        assertTrue(keys.getAllValues().get(1).startsWith("rate:devrereg:203.0.113.7:"));
    }

    @Test
    void reregistrationBudget_isEnforced() {
        when(ops.increment(anyString())).thenReturn(60L, 61L);

        assertDoesNotThrow(() -> limiter.checkReregistration("203.0.113.7"));
        var ex = assertThrows(RateLimitExceededException.class, () -> limiter.checkReregistration("203.0.113.7"));
        assertTrue(ex.getMessage().contains("60/hour"));
    }

    @Test
    void newRegistrationBudget_isUnchanged() {
        when(ops.increment(anyString())).thenReturn(11L);

        assertThrows(RateLimitExceededException.class, () -> limiter.check("203.0.113.7"));
    }
}
