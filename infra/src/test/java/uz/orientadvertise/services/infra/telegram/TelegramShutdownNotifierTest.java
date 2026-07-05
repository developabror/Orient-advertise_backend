package uz.orientadvertise.services.infra.telegram;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramShutdownNotifierTest {

    private OrientTelegramBot bot;
    private EnabledTelegramNotifier notifier;
    private TelegramBotProperties props;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private TelegramShutdownNotifier shutdown;

    @BeforeEach
    void setUp() {
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

        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> typedOps = mock(ValueOperations.class);
        ops = typedOps;
        when(redis.opsForValue()).thenReturn(ops);

        shutdown = new TelegramShutdownNotifier(notifier, props, bot, redis);
    }

    @Test
    void shutdown_setsCleanShutdownKey_andSendsToEachAuthorizedChat() {
        shutdown.shutdown();

        // Clean-shutdown timestamp written first — even if Telegram fails, gap analysis
        // on the next boot still sees the right value.
        verify(ops, times(1)).set(eq(TelegramShutdownNotifier.KEY_LAST_CLEAN_SHUTDOWN),
                anyString(), eq(Duration.ofDays(7)));
        verify(bot, times(2)).send(anyString(), anyString(), eq("Markdown"));
    }

    @Test
    void shutdown_emptyAuthorizedSet_writesKeyButSendsNothing() {
        // The key should still be written so the next start sees a clean shutdown,
        // but no messages go out (nothing to send to).
        var emptyProps = new TelegramBotProperties();
        emptyProps.setAuthorizedChatIds(Set.of());
        var sn = new TelegramShutdownNotifier(notifier, emptyProps, bot, redis);

        sn.shutdown();

        verify(ops, times(1)).set(eq(TelegramShutdownNotifier.KEY_LAST_CLEAN_SHUTDOWN),
                anyString(), any(Duration.class));
        verify(bot, never()).send(any(), any(), any());
    }

    @Test
    void shutdown_botNotRegistered_writesKeyButSkipsSend() {
        // Init failed → bot isn't registered → don't try to talk to Telegram.
        var unregistered = new EnabledTelegramNotifier(props,
                new TelegramRateLimiter(mock(StringRedisTemplate.class)),
                mock(TelegramOutboundQueue.class));
        // markRegistered NOT called.
        var sn = new TelegramShutdownNotifier(unregistered, props, bot, redis);

        sn.shutdown();

        verify(ops, times(1)).set(anyString(), anyString(), any(Duration.class));
        verify(bot, never()).send(any(), any(), any());
    }

    @Test
    void shutdown_redisFailureOnKeyWrite_stillAttemptsSend() {
        // Redis hiccup on the clean-shutdown write must not prevent the Telegram message.
        // Worst case: next boot misclassifies as unclean — but the operator still got the
        // shutdown notification.
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        shutdown.shutdown();

        verify(bot, times(2)).send(anyString(), anyString(), eq("Markdown"));
    }

    @Test
    void shutdown_slowSend_terminatesWithin3sBudget() {
        // Simulate a Telegram API that hangs forever. The whole shutdown call MUST
        // return within ~3 seconds — we can't block the JVM exit on a network outage.
        when(bot.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            Thread.sleep(10_000); // longer than the 3s budget
            return true;
        });

        long start = System.currentTimeMillis();
        shutdown.shutdown();
        long elapsed = System.currentTimeMillis() - start;

        // Allow some scheduler jitter — anything well under 5s is acceptable.
        assertTrue(elapsed < 5_000,
                "shutdown took " + elapsed + "ms; must abort within 3s budget");
        // Sends were dispatched (cancellation is fire-and-forget) — we just don't wait.
        verify(bot, atMost(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void payload_containsHostUptimeReasonTimestamp() {
        String body = shutdown.buildPayload();

        assertTrue(body.contains("🟢"), "severity emoji: " + body);
        assertTrue(body.contains("*Application shutting down*"));
        assertTrue(body.contains("```"), "Markdown code block fence");
        assertTrue(body.contains("host"));
        assertTrue(body.contains("uptime"));
        assertTrue(body.contains("reason    : graceful"),
                "default reason is 'graceful' when no signal hook fired (note column-width "
                        + "is now driven by the longest key, which is 'timestamp'='uptime'='reason'='host', all 9 chars or less): " + body);
        assertTrue(body.contains("timestamp"));
    }

    @Test
    void payload_signalReasonOverridesGraceful() {
        // When the signal-detection shutdown hook fires before @PreDestroy reads the flag,
        // the payload reflects the SIGTERM origin.
        shutdown.setReasonForTest("graceful (SIGTERM/SIGINT)");

        String body = shutdown.buildPayload();

        assertTrue(body.contains("reason    : graceful (SIGTERM/SIGINT)"));
    }
}
