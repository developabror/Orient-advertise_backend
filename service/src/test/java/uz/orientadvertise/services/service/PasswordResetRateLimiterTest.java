package uz.orientadvertise.services.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import uz.orientadvertise.services.common.exception.RateLimitExceededException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordResetRateLimiterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private PasswordResetRateLimiter limiter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        limiter = new PasswordResetRateLimiter(redis);
    }

    @Test
    void forgot_perIpOverBudget_throws429() {
        when(valueOps.increment(startsWith("pwdreset:forgot:ip:"))).thenReturn(6L); // default cap 5
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkForgotAllowed("1.2.3.4", "a@b.com"));
    }

    @Test
    void forgot_perEmailOverBudget_throws429() {
        when(valueOps.increment(startsWith("pwdreset:forgot:ip:"))).thenReturn(1L);
        when(valueOps.increment(startsWith("pwdreset:forgot:email:"))).thenReturn(4L); // default cap 3
        assertThrows(RateLimitExceededException.class,
                () -> limiter.checkForgotAllowed("1.2.3.4", "a@b.com"));
    }

    @Test
    void forgot_429MessageIsAddressBlind_identicalForBothGuards() {
        when(valueOps.increment(startsWith("pwdreset:forgot:ip:"))).thenReturn(6L);
        var ipEx = assertThrows(RateLimitExceededException.class,
                () -> limiter.checkForgotAllowed("1.2.3.4", "a@b.com"));

        when(valueOps.increment(startsWith("pwdreset:forgot:ip:"))).thenReturn(1L);
        when(valueOps.increment(startsWith("pwdreset:forgot:email:"))).thenReturn(4L);
        var emailEx = assertThrows(RateLimitExceededException.class,
                () -> limiter.checkForgotAllowed("1.2.3.4", "a@b.com"));

        // A distinguishable message would be an enumeration side channel.
        assertEquals(ipEx.getMessage(), emailEx.getMessage());
    }

    @Test
    void forgot_underBudget_allowed() {
        when(valueOps.increment(anyString())).thenReturn(1L);
        assertDoesNotThrow(() -> limiter.checkForgotAllowed("1.2.3.4", "a@b.com"));
    }

    @Test
    void reset_perIpOverBudget_throws429() {
        when(valueOps.increment(startsWith("pwdreset:reset:ip:"))).thenReturn(11L); // default cap 10
        assertThrows(RateLimitExceededException.class, () -> limiter.checkResetAllowed("1.2.3.4"));
    }

    @Test
    void failsOpen_onRedisError() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        // A Redis outage must not block recovery for everyone.
        assertDoesNotThrow(() -> limiter.checkForgotAllowed("1.2.3.4", "a@b.com"));
        assertDoesNotThrow(() -> limiter.checkResetAllowed("1.2.3.4"));
    }
}
