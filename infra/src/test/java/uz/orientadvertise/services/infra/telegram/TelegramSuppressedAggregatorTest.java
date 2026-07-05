package uz.orientadvertise.services.infra.telegram;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramSuppressedAggregatorTest {

    private StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private HashOperations hashOps;
    private OrientTelegramBot bot;
    private TelegramBotProperties props;
    private EnabledTelegramNotifier notifier;
    private TelegramSuppressedAggregator aggregator;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> h = mock(HashOperations.class);
        hashOps = h;
        when(redis.opsForHash()).thenReturn(hashOps);

        bot = mock(OrientTelegramBot.class);
        when(bot.send(anyString(), anyString(), eq("Markdown"))).thenReturn(true);
        props = new TelegramBotProperties();
        props.setEnabled(true);
        props.setToken("token");
        props.setUsername("test-bot");
        props.setAuthorizedChatIds(Set.of(100L, 200L));
        notifier = new EnabledTelegramNotifier(props,
                new TelegramRateLimiter(mock(StringRedisTemplate.class)),
                mock(TelegramOutboundQueue.class));
        notifier.markRegistered();

        aggregator = new TelegramSuppressedAggregator(redis, bot, props, notifier);
    }

    @Test
    void noSuppressedEntries_doesNotSend() {
        when(hashOps.entries(eq(TelegramRateLimiter.KEY_SUPPRESSED_COUNTS)))
                .thenReturn(java.util.Map.of());
        when(hashOps.entries(eq(TelegramRateLimiter.KEY_SUPPRESSED_SAMPLES)))
                .thenReturn(java.util.Map.of());

        aggregator.flushSummary();

        verify(bot, never()).send(any(), any(), any());
    }

    @Test
    void notRegistered_doesNotSend() {
        var unregistered = new EnabledTelegramNotifier(props,
                new TelegramRateLimiter(mock(StringRedisTemplate.class)),
                mock(TelegramOutboundQueue.class));
        // markRegistered NOT called.
        var aggUnreg = new TelegramSuppressedAggregator(redis, bot, props, unregistered);

        aggUnreg.flushSummary();

        verify(bot, never()).send(any(), any(), any());
    }

    @Test
    void emptyAuthorizedChats_drainsButDoesNotSend() {
        var emptyProps = new TelegramBotProperties();
        emptyProps.setAuthorizedChatIds(Set.of());
        var aggEmpty = new TelegramSuppressedAggregator(redis, bot, emptyProps, notifier);

        aggEmpty.flushSummary();

        verify(bot, never()).send(any(), any(), any());
        // Still cleans up the keys so they don't grow unbounded.
        verify(redis).delete(TelegramRateLimiter.KEY_SUPPRESSED_COUNTS);
        verify(redis).delete(TelegramRateLimiter.KEY_SUPPRESSED_SAMPLES);
    }

    @Test
    void buildsSummaryWithTotalAndTopOffender() {
        // 47 suppressed across 3 distinct hashes; the highest-count one is the "top".
        when(hashOps.entries(eq(TelegramRateLimiter.KEY_SUPPRESSED_COUNTS)))
                .thenReturn(java.util.Map.of(
                        "h1", "32",
                        "h2", "10",
                        "h3", "5"));
        when(hashOps.entries(eq(TelegramRateLimiter.KEY_SUPPRESSED_SAMPLES)))
                .thenReturn(java.util.Map.of(
                        "h1", "NullPointerException in DeviceController",
                        "h2", "Storage timeout",
                        "h3", "DB pool exhausted"));

        aggregator.flushSummary();

        // Two chats × one summary message = 2 sends, each containing the total + top.
        verify(bot, times(2)).send(anyString(), argThat(payload ->
                payload.contains("47 messages suppressed")
                && payload.contains("NullPointerException in DeviceController")
                && payload.contains("32 occurrences")
                && payload.contains("🟡")  // WARN severity emoji on the summary itself
        ), eq("Markdown"));
        // And the keys are cleared so the next 5-minute window starts fresh.
        verify(redis).delete(TelegramRateLimiter.KEY_SUPPRESSED_COUNTS);
        verify(redis).delete(TelegramRateLimiter.KEY_SUPPRESSED_SAMPLES);
    }

    @Test
    void sampleMissingForTopHash_fallsBackToUnknown() {
        // Defensive: if the counts hash has an entry the samples hash doesn't, the
        // summary still sends with a placeholder rather than crashing.
        when(hashOps.entries(eq(TelegramRateLimiter.KEY_SUPPRESSED_COUNTS)))
                .thenReturn(java.util.Map.of("h1", "5"));
        when(hashOps.entries(eq(TelegramRateLimiter.KEY_SUPPRESSED_SAMPLES)))
                .thenReturn(java.util.Map.of()); // no sample stored

        aggregator.flushSummary();

        verify(bot, atLeastOnce()).send(anyString(),
                argThat(p -> p.contains("(unknown)")), eq("Markdown"));
    }

    @Test
    void corruptCountValue_skippedNotCrashed() {
        // A non-numeric value in the counts hash (shouldn't happen, but Redis is shared
        // state). Skip the bad entry, build the summary from the rest.
        when(hashOps.entries(eq(TelegramRateLimiter.KEY_SUPPRESSED_COUNTS)))
                .thenReturn(java.util.Map.of("good", "3", "bad", "not-a-number"));
        when(hashOps.entries(eq(TelegramRateLimiter.KEY_SUPPRESSED_SAMPLES)))
                .thenReturn(java.util.Map.of("good", "Real error"));

        aggregator.flushSummary();

        verify(bot, atLeastOnce()).send(anyString(),
                argThat(p -> p.contains("3 messages suppressed") && p.contains("Real error")),
                eq("Markdown"));
    }

    @Test
    void redisException_swallowedSilently_noSend() {
        when(hashOps.entries(any()))
                .thenThrow(new RuntimeException("redis down"));

        aggregator.flushSummary();

        verify(bot, never()).send(any(), any(), any());
    }
}
