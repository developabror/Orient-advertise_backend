package uz.orientadvertise.services.infra.telegram;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Registers {@link OrientTelegramBot} with Telegram's long-polling API on
 * {@link ApplicationReadyEvent}, asynchronously, with retry. A Telegram outage at boot
 * MUST NOT block application startup — the rest of the app stays up; the bot reports
 * "not registered" until network reachability returns.
 *
 * <p><b>Retry policy.</b> Up to 5 attempts with exponential backoff (2s, 4s, 8s, 16s, 32s).
 * On final failure, logs at {@code ERROR} with explicit "CRITICAL:" prefix so the message
 * is easy to grep in a log aggregator. The application continues running normally and
 * the {@link EnabledTelegramNotifier} keeps reporting {@code isRegistered() == false},
 * so subsequent send calls are dropped at debug level rather than throwing.
 *
 * <p>Init runs on a one-shot daemon thread, not the Spring lifecycle thread, so
 * {@code ApplicationReadyEvent} returns immediately and the application accepts traffic
 * the moment Spring is up.
 */
@Component
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
public class TelegramBotInitializer {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotInitializer.class);

    static final int MAX_ATTEMPTS = 5;
    static final Duration INITIAL_BACKOFF = Duration.ofSeconds(2);

    private final TelegramBotsApi api;
    private final OrientTelegramBot bot;
    private final EnabledTelegramNotifier notifier;
    private final ObjectProvider<TelegramStartupNotifier> startupNotifier;
    private final Executor executor;
    private final Sleeper sleeper;

    // @Autowired designates THIS as the injection constructor. The class has three
    // constructors (this production one + two package-private test helpers); with none
    // marked, Spring cannot choose and falls back to a non-existent no-arg constructor,
    // failing bean creation with "No default constructor found" the moment the bot is
    // enabled. The conditional bean is never created when telegram.bot.enabled=false,
    // which is why this only surfaces on the enabled path.
    @Autowired
    public TelegramBotInitializer(TelegramBotsApi api,
                                    OrientTelegramBot bot,
                                    EnabledTelegramNotifier notifier,
                                    ObjectProvider<TelegramStartupNotifier> startupNotifier) {
        this(api, bot, notifier, startupNotifier, daemonExecutor(), defaultSleeper());
    }

    /** Test-friendly constructor — lets the unit test pass an inline executor + a no-op sleeper. */
    TelegramBotInitializer(TelegramBotsApi api,
                            OrientTelegramBot bot,
                            EnabledTelegramNotifier notifier,
                            ObjectProvider<TelegramStartupNotifier> startupNotifier,
                            Executor executor) {
        this(api, bot, notifier, startupNotifier, executor, defaultSleeper());
    }

    TelegramBotInitializer(TelegramBotsApi api,
                            OrientTelegramBot bot,
                            EnabledTelegramNotifier notifier,
                            ObjectProvider<TelegramStartupNotifier> startupNotifier,
                            Executor executor,
                            Sleeper sleeper) {
        this.api = api;
        this.bot = bot;
        this.notifier = notifier;
        this.startupNotifier = startupNotifier;
        this.executor = executor;
        this.sleeper = sleeper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize(ApplicationReadyEvent event) {
        // Hand off to a one-shot thread — never block the Spring lifecycle thread.
        CompletableFuture.runAsync(() -> registerWithRetry(event), executor);
    }

    void registerWithRetry(ApplicationReadyEvent event) {
        Duration backoff = INITIAL_BACKOFF;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                api.registerBot(bot);
                notifier.markRegistered();
                log.info("Telegram bot registered [username={}, attempt={}]",
                        bot.getBotUsername(), attempt);
                // Fire startup notification when present (it's a separate optional bean).
                var sn = startupNotifier.getIfAvailable();
                if (sn != null) {
                    sn.notifyStartup(event);
                }
                return;
            } catch (TelegramApiException | RuntimeException e) {
                log.warn("Telegram registration failed [attempt={}/{}]: {}",
                        attempt, MAX_ATTEMPTS, e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    // Final failure — explicit CRITICAL prefix for log-aggregator alerting.
                    // Do NOT rethrow; the app must continue running.
                    log.error("CRITICAL: Telegram bot registration failed after {} attempts. "
                            + "The application is up; Telegram notifications will be dropped "
                            + "until restart.", MAX_ATTEMPTS, e);
                    var sn = startupNotifier.getIfAvailable();
                    if (sn != null) {
                        sn.notifyStartupLocally(event,
                                "Telegram registration failed after " + MAX_ATTEMPTS + " retries");
                    }
                    return;
                }
                if (!sleeper.sleep(backoff)) {
                    log.warn("Telegram registration interrupted — aborting retries");
                    return;
                }
                backoff = backoff.multipliedBy(2);
            }
        }
    }

    /** Functional interface so tests can substitute a no-op sleep. */
    @FunctionalInterface
    interface Sleeper {
        /** Sleep for {@code d}; return false if interrupted (caller aborts). */
        boolean sleep(Duration d);
    }

    private static Sleeper defaultSleeper() {
        return d -> {
            try {
                Thread.sleep(d.toMillis());
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        };
    }

    private static Executor daemonExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "telegram-init");
            t.setDaemon(true);
            return t;
        });
    }
}
