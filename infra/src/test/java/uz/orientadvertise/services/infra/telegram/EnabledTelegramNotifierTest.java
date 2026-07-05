package uz.orientadvertise.services.infra.telegram;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class EnabledTelegramNotifierTest {

    private TelegramBotProperties props;
    private TelegramRateLimiter rateLimiter;
    private TelegramOutboundQueue queue;
    private EnabledTelegramNotifier notifier;

    @BeforeEach
    void setUp() {
        props = new TelegramBotProperties();
        props.setEnabled(true);
        props.setToken("test-token");
        props.setUsername("test-bot");
        props.setAuthorizedChatIds(Set.of(100L, 200L));
        rateLimiter = new TelegramRateLimiter(
                mock(org.springframework.data.redis.core.StringRedisTemplate.class));
        queue = mock(TelegramOutboundQueue.class);
        notifier = new EnabledTelegramNotifier(props, rateLimiter, queue);
        // Default = not yet registered. Tests opt-in via markRegistered().
    }

    @Test
    void isEnabled_returnsTrue() {
        assertTrue(notifier.isEnabled());
    }

    @Test
    void notRegistered_dropsMessage_evenForAuthorized() {
        // Pre-registration sends are dropped — no race condition where a @PostConstruct
        // hook tries to send before TelegramBotInitializer has finished.
        notifier.sendMessage("100", "hello");

        verify(queue, never()).enqueue(any());
        assertFalse(notifier.isRegistered());
    }

    @Test
    void registered_authorizedChat_enqueuesSingleChatMessage() {
        notifier.markRegistered();

        notifier.sendMessage("100", "hello");

        var captor = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(queue).enqueue(captor.capture());
        OutboundMessage msg = captor.getValue();
        assertEquals("hello", msg.text());
        assertEquals(Set.of(100L), msg.remainingChatIds());
        assertEquals(0, msg.attempt());
        assertEquals(Severity.INFO, msg.severity());
    }

    @Test
    void registered_unauthorizedChat_dropsMessage() {
        notifier.markRegistered();

        notifier.sendMessage("999", "hello");

        verify(queue, never()).enqueue(any());
    }

    @Test
    void registered_blankChatId_dropsMessage() {
        notifier.markRegistered();

        notifier.sendMessage("", "hello");
        notifier.sendMessage(null, "hello");

        verify(queue, never()).enqueue(any());
    }

    @Test
    void registered_nonNumericChatId_dropsMessage() {
        notifier.markRegistered();

        notifier.sendMessage("abc", "hello");

        verify(queue, never()).enqueue(any());
    }

    @Test
    void emptyAllowList_initLogsWarning_andEverySendIsDropped() {
        var emptyProps = new TelegramBotProperties();
        emptyProps.setAuthorizedChatIds(Set.of());
        var emptyNotifier = new EnabledTelegramNotifier(emptyProps, rateLimiter, queue);
        emptyNotifier.markRegistered();

        emptyNotifier.sendMessage("100", "hello");

        verify(queue, never()).enqueue(any());
    }

    @Test
    void sendMarkdown_passesParseModeThroughToOutboundMessage() {
        notifier.markRegistered();

        notifier.sendMarkdown("100", "*bold*");

        var captor = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(queue).enqueue(captor.capture());
        assertEquals("Markdown", captor.getValue().parseMode());
    }

    @Test
    void broadcastMarkdown_enqueuesSingleMessageForAllAuthorizedChats() {
        // One queue entry per logical broadcast — the consumer fans out to chats.
        notifier.markRegistered();

        notifier.broadcastMarkdown("alert");

        var captor = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(queue, times(1)).enqueue(captor.capture());
        OutboundMessage msg = captor.getValue();
        assertEquals(props.getAuthorizedChatIds(), msg.remainingChatIds());
        assertEquals("Markdown", msg.parseMode());
    }

    @Test
    void broadcastMarkdown_notRegistered_doesNotEnqueue() {
        // markRegistered NOT called.
        notifier.broadcastMarkdown("alert");

        verify(queue, never()).enqueue(any());
    }

    @Test
    void broadcastMarkdown_emptyAllowList_doesNotEnqueue() {
        var emptyProps = new TelegramBotProperties();
        emptyProps.setAuthorizedChatIds(Set.of());
        var emptyNotifier = new EnabledTelegramNotifier(emptyProps, rateLimiter, queue);
        emptyNotifier.markRegistered();

        emptyNotifier.broadcastMarkdown("alert");

        verify(queue, never()).enqueue(any());
    }

    @Test
    void broadcastMarkdown_severityForwardedToOutboundMessage() {
        // Severity drives both the rate limiter (FATAL bypass) and the queue's overflow
        // policy (FATAL is protected from drop-oldest). Must propagate cleanly.
        notifier.markRegistered();

        notifier.broadcastMarkdown("crit", Severity.FATAL);

        var captor = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(queue).enqueue(captor.capture());
        assertEquals(Severity.FATAL, captor.getValue().severity());
    }
}
