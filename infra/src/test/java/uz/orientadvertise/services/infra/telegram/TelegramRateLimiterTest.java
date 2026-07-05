package uz.orientadvertise.services.infra.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;
import uz.orientadvertise.services.infra.telegram.TelegramRateLimiter.Outcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramRateLimiterTest {

    private StringRedisTemplate redis;
    private ZSetOperations<String, String> zops;
    @SuppressWarnings("rawtypes")
    private HashOperations hashOps;
    private TelegramRateLimiter limiter;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> z = mock(ZSetOperations.class);
        zops = z;
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> h = mock(HashOperations.class);
        hashOps = h;
        when(redis.opsForZSet()).thenReturn(zops);
        when(redis.opsForHash()).thenReturn(hashOps);
        // Default: empty windows.
        when(zops.zCard(anyString())).thenReturn(0L);
        limiter = new TelegramRateLimiter(redis);
    }

    @Test
    void fatalSeverity_bypassesRateCheck_neverHitsRedis() {
        // Spec: "Critical level messages bypass rate limiting and always send."
        var d = limiter.tryAcquire("anything", Severity.FATAL);

        assertEquals(Outcome.BYPASS, d.outcome());
        assertTrue(d.shouldSend());
        verify(redis, never()).opsForZSet();
    }

    @Test
    void underBothLimits_returnsAllowed_andRecordsAcquire() {
        var d = limiter.tryAcquire("hello", Severity.INFO);

        assertEquals(Outcome.ALLOWED, d.outcome());
        // Sliding-window correctness depends on prune-then-count-then-add ordering —
        // assert each step happened in the right call list.
        verify(zops).removeRangeByScore(eq(TelegramRateLimiter.KEY_GLOBAL), eq(0d), anyDouble());
        verify(zops, times(2)).zCard(anyString()); // global + per-hash
        verify(zops, times(2)).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void globalLimitReached_returnsSuppressed_andIncrementsCounter() {
        when(zops.zCard(eq(TelegramRateLimiter.KEY_GLOBAL))).thenReturn((long) TelegramRateLimiter.GLOBAL_LIMIT);

        var d = limiter.tryAcquire("hello", Severity.WARN);

        assertEquals(Outcome.SUPPRESSED, d.outcome());
        assertTrue(!d.shouldSend());
        verify(hashOps).increment(eq(TelegramRateLimiter.KEY_SUPPRESSED_COUNTS), anyString(), eq(1L));
        verify(hashOps).putIfAbsent(eq(TelegramRateLimiter.KEY_SUPPRESSED_SAMPLES), anyString(), anyString());
    }

    @Test
    void perHashLimitReached_globalUnderLimit_stillSuppressed() {
        // Identical-message cap (5/5min). Global window has plenty of room — but the
        // per-hash window for this exact text is full.
        when(zops.zCard(eq(TelegramRateLimiter.KEY_GLOBAL))).thenReturn(0L);
        when(zops.zCard(org.mockito.ArgumentMatchers.startsWith(TelegramRateLimiter.KEY_MSG_PREFIX)))
                .thenReturn((long) TelegramRateLimiter.PER_HASH_LIMIT);

        var d = limiter.tryAcquire("repeated message", Severity.ERROR);

        assertEquals(Outcome.SUPPRESSED, d.outcome());
        verify(hashOps).increment(eq(TelegramRateLimiter.KEY_SUPPRESSED_COUNTS), anyString(), eq(1L));
    }

    @Test
    void differentMessages_underPerHashLimit_eachAllowed() {
        // Three distinct messages — each gets its own per-hash key, so none individually
        // breaches the 5/5min cap.
        when(zops.zCard(anyString())).thenReturn(0L);

        for (int i = 0; i < 3; i++) {
            assertEquals(Outcome.ALLOWED,
                    limiter.tryAcquire("message-" + i, Severity.WARN).outcome());
        }
    }

    @Test
    void redisException_fallsBackToInMemoryLimiter() {
        // Spec: "Redis unavailable — fall back to in-memory rate limiter (loses state on
        // restart but still prevents flooding)." We simulate Redis blowing up on the
        // very first call and verify the in-memory limiter answered instead.
        doThrow(new RuntimeException("connection refused"))
                .when(zops).removeRangeByScore(anyString(), anyDouble(), anyDouble());

        for (int i = 0; i < TelegramRateLimiter.GLOBAL_LIMIT; i++) {
            assertEquals(Outcome.ALLOWED,
                    limiter.tryAcquire("msg-" + i, Severity.WARN).outcome());
        }
        // The 31st must trip the in-memory global limit.
        var d31 = limiter.tryAcquire("msg-tipping-point", Severity.WARN);
        assertEquals(Outcome.SUPPRESSED, d31.outcome());
    }

    @Test
    void inMemoryFallback_perHashLimit_alsoEnforced() {
        // Same message hash 6 times — 5 succeed, 6th is suppressed by the per-hash window.
        doThrow(new RuntimeException("redis dead"))
                .when(zops).removeRangeByScore(anyString(), anyDouble(), anyDouble());

        for (int i = 0; i < TelegramRateLimiter.PER_HASH_LIMIT; i++) {
            assertEquals(Outcome.ALLOWED,
                    limiter.tryAcquire("identical", Severity.WARN).outcome());
        }
        assertEquals(Outcome.SUPPRESSED,
                limiter.tryAcquire("identical", Severity.WARN).outcome());
    }

    @Test
    void sampleFrom_stripsMarkdownAndUsesFirstNonEmptyLine() {
        // The sample line is what shows up in the suppression summary as "top error".
        // Markdown emphasis and leading newlines must not survive into that label.
        assertEquals("Application started",
                TelegramRateLimiter.sampleFrom("\n*Application started*\n```\nhost..."));
        assertEquals("HTTP 500 — Unhandled exception",
                TelegramRateLimiter.sampleFrom("🔴 *ERROR* — HTTP 500 — Unhandled exception\nbody"));
        assertEquals("(empty)", TelegramRateLimiter.sampleFrom(""));
        assertEquals("(empty)", TelegramRateLimiter.sampleFrom(null));
    }

    @Test
    void hashing_identicalMessages_identicalHashes() {
        // Per-hash dedup correctness depends on a stable hash. Same input, same output.
        String h1 = TelegramRateLimiter.sha256Hex("the same");
        String h2 = TelegramRateLimiter.sha256Hex("the same");
        String h3 = TelegramRateLimiter.sha256Hex("different");
        assertEquals(h1, h2);
        assertTrue(!h1.equals(h3));
        assertEquals(16, h1.length(), "16-char hash prefix");
    }

    @Test
    void redisRecordSuppressedException_swallowedSilently() {
        // recordSuppressed is a side-effect on top of the suppression decision — its
        // own Redis errors must not prevent the SUPPRESSED outcome from being returned.
        when(zops.zCard(eq(TelegramRateLimiter.KEY_GLOBAL))).thenReturn((long) TelegramRateLimiter.GLOBAL_LIMIT);
        doThrow(new RuntimeException("redis blip"))
                .when(hashOps).increment(anyString(), any(), anyLong());

        var d = limiter.tryAcquire("hello", Severity.WARN);

        assertEquals(Outcome.SUPPRESSED, d.outcome());
    }

    @Test
    void zCardReturnsNull_treatedAsEmpty() {
        // Defensive against Redis returning null (shouldn't happen on a clean cluster
        // but Mockito sometimes returns null; the limiter must handle it.)
        when(zops.zCard(anyString())).thenReturn(null);

        var d = limiter.tryAcquire("hello", Severity.INFO);

        assertEquals(Outcome.ALLOWED, d.outcome());
    }
}
