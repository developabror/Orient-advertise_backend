package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyRateLimiterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ApiKeyRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> typed = mock(ValueOperations.class);
        ops = typed;
        when(redis.opsForValue()).thenReturn(ops);
        limiter = new ApiKeyRateLimiter(redis);
    }

    @Test
    void firstHitInBucket_setsTtl() {
        when(ops.increment(anyString())).thenReturn(1L);

        var d = limiter.tryAcquire(7L);

        assertTrue(d.allowed());
        assertEquals(ApiKeyRateLimiter.LIMIT_PER_HOUR, d.limit());
        assertEquals(ApiKeyRateLimiter.LIMIT_PER_HOUR - 1, d.remaining());
        // Reset is the start of the next hour bucket — strictly in the future.
        assertTrue(d.resetEpochSeconds() > Instant.now().getEpochSecond());
        verify(redis).expire(anyString(), eq(ApiKeyRateLimiter.BUCKET_TTL));
    }

    @Test
    void secondHitInSameBucket_doesNotResetTtl() {
        when(ops.increment(anyString())).thenReturn(2L);
        var d = limiter.tryAcquire(7L);
        assertTrue(d.allowed());
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void atLimit_isAllowed_remainingZero() {
        // Fixed-window: hit #100 is allowed, hit #101 is rejected.
        when(ops.increment(anyString())).thenReturn(ApiKeyRateLimiter.LIMIT_PER_HOUR);
        var d = limiter.tryAcquire(7L);
        assertTrue(d.allowed());
        assertEquals(0L, d.remaining());
    }

    @Test
    void overLimit_isRejected_remainingClampedAtZero() {
        when(ops.increment(anyString())).thenReturn(ApiKeyRateLimiter.LIMIT_PER_HOUR + 1);
        var d = limiter.tryAcquire(7L);
        assertFalse(d.allowed());
        assertEquals(0L, d.remaining());
    }

    @Test
    void redisReturnsNull_failsOpenWithConservativeRemaining() {
        // Defensive path: a healthy Redis never returns null from INCR, but if it does
        // we must not deadlock the API. Allow the request and report remaining=limit-1.
        when(ops.increment(anyString())).thenReturn(null);
        var d = limiter.tryAcquire(7L);
        assertTrue(d.allowed());
        assertEquals(ApiKeyRateLimiter.LIMIT_PER_HOUR - 1, d.remaining());
    }

    @Test
    void perKeyScoping_distinctKeysIncrementDistinctBuckets() {
        // Each invocation increments and we want to confirm the redis key includes the
        // apiKeyId. We capture the keys passed to increment and assert both ids appear.
        var captured = new java.util.ArrayList<String>();
        when(ops.increment(anyString())).thenAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return 1L;
        });

        limiter.tryAcquire(1L);
        limiter.tryAcquire(2L);

        assertEquals(2, captured.size());
        assertTrue(captured.get(0).contains(":apikey:1:"));
        assertTrue(captured.get(1).contains(":apikey:2:"));
    }

    @Test
    void resetEpoch_isAtNextHourBoundary() {
        when(ops.increment(anyString())).thenReturn(1L);
        var d = limiter.tryAcquire(7L);
        // Reset must be exactly on a 3600-second boundary.
        assertEquals(0L, d.resetEpochSeconds() % 3600L);
    }

    @Test
    void simulatedHourOf101Requests_first100Allowed_101stRejected() {
        // Simulate Redis INCR semantics with a real counter; verify the threshold
        // behavior end-to-end against the limiter.
        var counter = new AtomicLong(0);
        when(ops.increment(anyString())).thenAnswer(inv -> counter.incrementAndGet());

        int allowed = 0;
        int rejected = 0;
        for (int i = 0; i < 101; i++) {
            var d = limiter.tryAcquire(7L);
            if (d.allowed()) allowed++;
            else rejected++;
        }
        assertEquals(100, allowed);
        assertEquals(1, rejected);
        verify(ops, atLeastOnce()).increment(anyString());
    }
}
