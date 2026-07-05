package uz.orientadvertise.services.infra.telegram;

import java.util.Set;
import java.util.concurrent.TimeoutException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrientTelegramBotMetricsTest {

    private OrientTelegramBot bot;
    private TelegramMetrics metrics;
    private TelegramFailureRateMonitor monitor;
    private DisabledChatRegistry disabledRegistry;

    @BeforeEach
    void setUp() {
        var props = new TelegramBotProperties();
        props.setEnabled(true);
        props.setToken("test-token");
        props.setUsername("test-bot");
        props.setAuthorizedChatIds(Set.of(100L, 200L));
        bot = spy(new OrientTelegramBot(props));

        metrics = new TelegramMetrics(new SimpleMeterRegistry());
        monitor = new TelegramFailureRateMonitor();
        disabledRegistry = mock(DisabledChatRegistry.class);

        bot.setMetrics(metrics);
        bot.setFailureRateMonitor(monitor);
        bot.setDisabledChatRegistry(disabledRegistry);
    }

    // ---------- success path ----------

    @Test
    void successfulSend_recordsSuccessOnMetricsAndMonitor() throws Exception {
        // Stub the underlying execute() to succeed silently.
        doNothingExecute();

        boolean sent = bot.send("100", "hello", null);

        assertTrue(sent);
        assertEquals(1.0, metrics.sentTotal());
        assertEquals(0.0, metrics.failedTotal());
        assertEquals(0.0, monitor.currentFailureRate());
    }

    // ---------- 403 detection ----------

    @Test
    void send_403Forbidden_classified_andChatDisabled() throws Exception {
        // 403 with the Telegram-specific error code → classified as FORBIDDEN_403,
        // chat id passed to disabledRegistry.disable.
        var apiEx = new TestApiException(
                "Forbidden: bot was kicked from the supergroup chat", 403);
        doThrow(apiEx).when(bot).execute(any(SendMessage.class));

        boolean sent = bot.send("100", "hello", null);

        assertFalse(sent);
        assertEquals(0.0, metrics.sentTotal());
        assertEquals(1.0, metrics.failedTotal());
        assertEquals("FORBIDDEN_403",
                metrics.lastFailureReason().substring(0, "FORBIDDEN_403".length()));
        verify(disabledRegistry).disable(
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.contains("kicked"));
    }

    @Test
    void send_otherErrorCode_doesNotDisableChat() throws Exception {
        // 400 / 500 / etc. — recorded as a failure but the chat is NOT auto-disabled.
        // Disabling on transient errors would orphan healthy chats.
        var apiEx = new TestApiException("Bad Request: chat not found", 400);
        doThrow(apiEx).when(bot).execute(any(SendMessage.class));

        bot.send("100", "hello", null);

        assertEquals(1.0, metrics.failedTotal());
        verify(disabledRegistry, never()).disable(any(), any());
    }

    @Test
    void send_genericTelegramApiException_classifiedAsOther() throws Exception {
        doThrow(new TelegramApiException("library bug")).when(bot).execute(any(SendMessage.class));

        bot.send("100", "hello", null);

        assertEquals(1.0, metrics.failedTotal());
        assertTrue(metrics.lastFailureReason().contains("OTHER"));
        verify(disabledRegistry, never()).disable(any(), any());
    }

    @Test
    void send_timeoutCause_classifiedAsTimeout() throws Exception {
        var apiEx = new TelegramApiException("send timed out", new TimeoutException("5s"));
        doThrow(apiEx).when(bot).execute(any(SendMessage.class));

        bot.send("100", "hello", null);

        assertTrue(metrics.lastFailureReason().contains("TIMEOUT"),
                "classified as TIMEOUT: " + metrics.lastFailureReason());
    }

    @Test
    void send_ioExceptionCause_classifiedAsNetwork() throws Exception {
        var apiEx = new TelegramApiException("send failed",
                new java.io.IOException("connection refused"));
        doThrow(apiEx).when(bot).execute(any(SendMessage.class));

        bot.send("100", "hello", null);

        assertTrue(metrics.lastFailureReason().contains("NETWORK"));
    }

    // ---------- disabled-chat short-circuit ----------

    @Test
    void send_toDisabledChat_skipped_noNetworkCall_noMetricChange() throws Exception {
        when(disabledRegistry.isDisabled(100L)).thenReturn(true);

        boolean sent = bot.send("100", "hello", null);

        assertFalse(sent, "skipped → returns false (caller treats as unsent)");
        verify(bot, never()).execute(any(SendMessage.class));
        assertEquals(0.0, metrics.sentTotal());
        assertEquals(0.0, metrics.failedTotal(),
                "skipping a disabled chat is NOT a metric failure — it's intentional");
    }

    @Test
    void send_toEnabledChat_proceeds_normally() throws Exception {
        when(disabledRegistry.isDisabled(100L)).thenReturn(false);
        doNothingExecute();

        boolean sent = bot.send("100", "hello", null);

        assertTrue(sent);
        assertEquals(1.0, metrics.sentTotal());
    }

    // ---------- classifyFailure unit tests ----------

    @Test
    void classifyFailure_403_isForbidden() {
        var ex = new TestApiException("Forbidden: bot blocked", 403);
        assertEquals(TelegramMetrics.FailureReason.FORBIDDEN_403,
                OrientTelegramBot.classifyFailure(ex));
    }

    @Test
    void classifyFailure_400_isOther() {
        var ex = new TestApiException("Bad Request", 400);
        assertEquals(TelegramMetrics.FailureReason.OTHER,
                OrientTelegramBot.classifyFailure(ex));
    }

    @Test
    void classifyFailure_messageContainsTimeout_isTimeout() {
        var ex = new TelegramApiException("read timed out");
        assertEquals(TelegramMetrics.FailureReason.TIMEOUT,
                OrientTelegramBot.classifyFailure(ex));
    }

    @Test
    void classifyFailure_messageContainsConnection_isNetwork() {
        var ex = new TelegramApiException("unable to execute request");
        assertEquals(TelegramMetrics.FailureReason.NETWORK,
                OrientTelegramBot.classifyFailure(ex));
    }

    // ---------- metrics absence (test-slice safety) ----------

    @Test
    void send_withoutWiredMetrics_stillWorks_noNpe() throws Exception {
        var props = new TelegramBotProperties();
        props.setToken("t");
        props.setUsername("u");
        props.setAuthorizedChatIds(Set.of());
        var bareBot = spy(new OrientTelegramBot(props));
        // No metrics, no monitor, no registry wired.
        doNothingExecute(bareBot);

        boolean sent = bareBot.send("100", "hi", null);
        assertTrue(sent, "bot must work without optional collaborators wired");
    }

    // ---------- helpers ----------

    private void doNothingExecute() throws TelegramApiException {
        doNothingExecute(bot);
    }

    private static void doNothingExecute(OrientTelegramBot target) throws TelegramApiException {
        // execute returns the BotApiMethod's response object; we don't care about the
        // return value, so any non-throw is fine.
        org.mockito.Mockito.doReturn(null).when(target).execute(any(SendMessage.class));
    }

    /**
     * TelegramApiRequestException's public constructors don't accept an int error code
     * directly (they take ApiResponse&lt;?&gt; or Throwable). Subclass for tests so we
     * can inject the code we want to classify.
     */
    private static final class TestApiException extends TelegramApiRequestException {
        private final int code;
        TestApiException(String msg, int code) {
            super(msg);
            this.code = code;
        }
        @Override public Integer getErrorCode() { return code; }
    }
}
