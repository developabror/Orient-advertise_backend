package uz.orientadvertise.services.infra.telegram;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramStartupNotifierTest {

    private OrientTelegramBot bot;
    private EnabledTelegramNotifier notifier;
    private TelegramOutboundQueue outboundQueue;
    private TelegramBotProperties props;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private Environment environment;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BuildProperties> buildProvider = mock(ObjectProvider.class);
    private TelegramStartupNotifier startupNotifier;

    @BeforeEach
    void setUp() {
        bot = mock(OrientTelegramBot.class);
        props = new TelegramBotProperties();
        props.setEnabled(true);
        props.setToken("token");
        props.setUsername("test-bot");
        props.setAuthorizedChatIds(Set.of(100L, 200L));
        outboundQueue = mock(TelegramOutboundQueue.class);
        notifier = new EnabledTelegramNotifier(props,
                new TelegramRateLimiter(mock(StringRedisTemplate.class)),
                outboundQueue);
        notifier.markRegistered();

        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> typedOps = mock(ValueOperations.class);
        ops = typedOps;
        when(redis.opsForValue()).thenReturn(ops);
        // Default gap-analysis state = "first run" (no prior keys). Tests that need a
        // specific clean/unclean classification override the .get() stubs below.
        when(ops.get("telegram:last-heartbeat")).thenReturn(null);
        when(ops.get("telegram:last-clean-shutdown")).thenReturn(null);

        environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});

        var bp = mock(BuildProperties.class);
        when(bp.getVersion()).thenReturn("1.0.59");
        when(buildProvider.getIfAvailable()).thenReturn(bp);

        startupNotifier = new TelegramStartupNotifier(notifier, props, redis, environment, buildProvider);
    }

    @Test
    void notifyStartup_acquiresCooldown_andSendsToEachAuthorizedChat() {
        // Cooldown succeeds → both authorized chats get the markdown payload.
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        startupNotifier.notifyStartup(readyEvent());

        verify(redis.opsForValue()).setIfAbsent(eq("telegram:startup-cooldown"), anyString(), eq(Duration.ofSeconds(60)));
        // Each authorized chat → one queue enqueue (consumer fans out per-chat-id later).
        verify(outboundQueue, times(2)).enqueue(any(OutboundMessage.class));
    }

    @Test
    void cooldownAlreadyHeld_skipsSend_crashLoopProtection() {
        // SETNX returns false → another instance sent within the last 60s. Skip.
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        startupNotifier.notifyStartup(readyEvent());

        verify(outboundQueue, never()).enqueue(any());
    }

    @Test
    void redisFailure_skipsSend_failsClosed() {
        // Redis error → conservative: skip (don't risk flooding).
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        startupNotifier.notifyStartup(readyEvent());

        verify(outboundQueue, never()).enqueue(any());
    }

    @Test
    void emptyAuthorizedSet_skipsSend_noCooldownConsumed() {
        // Don't burn the 60s cooldown when there's nothing to send anyway.
        var emptyProps = new TelegramBotProperties();
        emptyProps.setAuthorizedChatIds(Set.of());
        var emptyNotifier = new TelegramStartupNotifier(notifier, emptyProps, redis, environment, buildProvider);

        emptyNotifier.notifyStartup(readyEvent());

        verify(redis, never()).opsForValue();
        verify(outboundQueue, never()).enqueue(any());
    }

    @Test
    void payload_containsRequiredMetadataFields() {
        String body = startupNotifier.buildPayload(readyEvent());

        // Header now produced by TelegramMessageBuilder: severity emoji + bold label + " — " + title.
        assertTrue(body.contains("🟢"), "INFO severity emoji: " + body);
        assertTrue(body.contains("*INFO*"), "bold severity label");
        assertTrue(body.contains("*Application started*"), "bold title");
        assertTrue(body.contains("```"), "code block fence");
        assertTrue(body.contains("host"), "hostname row");
        assertTrue(body.contains("version     : 1.0.59"), "version from BuildProperties");
        assertTrue(body.contains("environment : prod"), "active profile");
        assertTrue(body.contains("duration    : 3s"), "startup duration in seconds");
        assertTrue(body.contains("timestamp"), "timestamp row");
    }

    @Test
    void payload_buildPropertiesAbsent_fallsBackToUnknown() {
        @SuppressWarnings("unchecked")
        ObjectProvider<BuildProperties> emptyBuild = mock(ObjectProvider.class);
        when(emptyBuild.getIfAvailable()).thenReturn(null);
        var sn = new TelegramStartupNotifier(notifier, props, redis, environment, emptyBuild);

        String body = sn.buildPayload(readyEvent());

        assertTrue(body.contains("version     : unknown"));
    }

    @Test
    void notifyStartupLocally_logsButDoesNotSendOrConsumeCooldown() {
        // Used by the initializer when registration ultimately failed — we still want
        // the same metadata in the boot log for ops visibility, but we must not send
        // anything or consume the cooldown.
        startupNotifier.notifyStartupLocally(readyEvent(), "init failed");
        verify(outboundQueue, never()).enqueue(any());
        // The cooldown SETNX is the side effect that matters — confirm it wasn't called.
        verify(ops, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void payload_gapAnalysis_firstRun_whenNoKeys() {
        when(ops.get("telegram:last-heartbeat")).thenReturn(null);
        when(ops.get("telegram:last-clean-shutdown")).thenReturn(null);

        String body = startupNotifier.buildPayload(readyEvent());

        assertTrue(body.contains("previous    : first run"));
    }

    @Test
    void payload_gapAnalysis_clean_whenShutdownNewerThanHeartbeat() {
        long now = System.currentTimeMillis();
        when(ops.get("telegram:last-heartbeat")).thenReturn(String.valueOf(now - 60_000));
        when(ops.get("telegram:last-clean-shutdown")).thenReturn(String.valueOf(now - 10_000));

        String body = startupNotifier.buildPayload(readyEvent());

        assertTrue(body.contains("previous    : clean ("),
                "should classify as clean: " + body);
    }

    @Test
    void payload_gapAnalysis_unclean_whenHeartbeatNewerThanShutdown() {
        // The diagnostic case: previous JVM was alive recently (heartbeat) but never
        // wrote a clean-shutdown marker. SIGKILL / OOM / hard crash.
        long now = System.currentTimeMillis();
        when(ops.get("telegram:last-heartbeat")).thenReturn(String.valueOf(now - 5_000));
        when(ops.get("telegram:last-clean-shutdown")).thenReturn(String.valueOf(now - 3_600_000));

        String body = startupNotifier.buildPayload(readyEvent());

        assertTrue(body.contains("UNCLEAN"),
                "should flag unclean previous run: " + body);
        assertTrue(body.contains("SIGKILL"),
                "should mention SIGKILL hint: " + body);
    }

    @Test
    void payload_gapAnalysis_unclean_whenShutdownMissing() {
        // No clean-shutdown record at all but a fresh heartbeat — same diagnosis.
        when(ops.get("telegram:last-heartbeat"))
                .thenReturn(String.valueOf(System.currentTimeMillis() - 5_000));
        when(ops.get("telegram:last-clean-shutdown")).thenReturn(null);

        String body = startupNotifier.buildPayload(readyEvent());

        assertTrue(body.contains("UNCLEAN"));
    }

    private static ApplicationReadyEvent readyEvent() {
        var event = mock(ApplicationReadyEvent.class);
        when(event.getTimeTaken()).thenReturn(Duration.ofSeconds(3));
        return event;
    }
}
