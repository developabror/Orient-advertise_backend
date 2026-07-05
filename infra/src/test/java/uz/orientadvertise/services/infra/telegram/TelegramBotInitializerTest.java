package uz.orientadvertise.services.infra.telegram;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the retry/recovery contract for the Telegram bot init.
 *
 * <p>Uses the test-friendly constructor with an inline executor (Runnable::run) so
 * each call to {@code initialize} runs synchronously and we can assert on outcomes
 * without {@code Thread.sleep} or polling. Backoff sleeps are still respected — the
 * tests use single-attempt or sub-tests with a custom-tuned bot init that doesn't
 * actually wait long.
 */
class TelegramBotInitializerTest {

    private TelegramBotsApi api;
    private OrientTelegramBot bot;
    private EnabledTelegramNotifier notifier;
    private TelegramStartupNotifier startupNotifier;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<TelegramStartupNotifier> startupProvider = mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        api = mock(TelegramBotsApi.class);
        var props = new TelegramBotProperties();
        props.setEnabled(true);
        props.setToken("test-token");
        props.setUsername("test-bot");
        props.setAuthorizedChatIds(Set.of(100L));
        bot = mock(OrientTelegramBot.class);
        when(bot.getBotUsername()).thenReturn("test-bot");
        notifier = new EnabledTelegramNotifier(props,
                new TelegramRateLimiter(mock(org.springframework.data.redis.core.StringRedisTemplate.class)),
                mock(TelegramOutboundQueue.class));
        startupNotifier = mock(TelegramStartupNotifier.class);
        when(startupProvider.getIfAvailable()).thenReturn(startupNotifier);
    }

    @Test
    void firstAttemptSucceeds_marksRegistered_andFiresStartupNotification() throws Exception {
        // registerBot returns a BotSession (not void) — leave default return (null);
        // we only care that no exception is thrown.
        var init = newInit();

        init.registerWithRetry(readyEvent());

        assertTrue(notifier.isRegistered());
        verify(api, times(1)).registerBot(bot);
        verify(startupNotifier, times(1)).notifyStartup(any());
    }

    @Test
    void registrationFailsThenSucceeds_retriesAndStillMarksRegistered() throws Exception {
        // Mockito-stubbed sequence: first 2 calls throw, 3rd succeeds (returns null).
        when(api.registerBot(bot))
                .thenThrow(new TelegramApiException("fail-1"))
                .thenThrow(new TelegramApiException("fail-2"))
                .thenReturn(null);
        var init = newInitFastBackoff();

        init.registerWithRetry(readyEvent());

        assertTrue(notifier.isRegistered());
        verify(api, times(3)).registerBot(bot);
        verify(startupNotifier, times(1)).notifyStartup(any());
    }

    @Test
    void allFiveRetriesFail_logsCriticalAndContinuesWithoutThrowing() throws Exception {
        doThrow(new TelegramApiException("permanent")).when(api).registerBot(any());
        var init = newInitFastBackoff();

        // Must not throw — the app must continue running even if Telegram is permanently down.
        init.registerWithRetry(readyEvent());

        assertFalse(notifier.isRegistered());
        verify(api, times(TelegramBotInitializer.MAX_ATTEMPTS)).registerBot(any());
        // On terminal failure, the startup notifier is asked to log the intended payload
        // locally instead of skipping silently.
        verify(startupNotifier, times(1)).notifyStartupLocally(any(), any());
        verify(startupNotifier, never()).notifyStartup(any());
    }

    @Test
    void noStartupNotifierBean_doesNotThrow() throws Exception {
        // The startup notifier is a separate optional bean. Init must work even if it
        // isn't on the classpath / bean container.
        @SuppressWarnings("unchecked")
        ObjectProvider<TelegramStartupNotifier> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        var init = new TelegramBotInitializer(api, bot, notifier, emptyProvider,
                Runnable::run, d -> true);
        // Default return on the mock = null, which is fine for a non-void method
        // that throws only on failure. No explicit stub needed.

        init.registerWithRetry(readyEvent());

        assertTrue(notifier.isRegistered());
        verify(api, atLeastOnce()).registerBot(bot);
    }

    private TelegramBotInitializer newInit() {
        // Inline executor + no-op sleeper — runs the registration synchronously and
        // skips backoff sleeps so tests finish in milliseconds.
        return new TelegramBotInitializer(api, bot, notifier, startupProvider,
                Runnable::run, d -> true);
    }

    private TelegramBotInitializer newInitFastBackoff() {
        return newInit();
    }

    private static ApplicationReadyEvent readyEvent() {
        var event = mock(ApplicationReadyEvent.class);
        when(event.getTimeTaken()).thenReturn(java.time.Duration.ofSeconds(3));
        return event;
    }
}
