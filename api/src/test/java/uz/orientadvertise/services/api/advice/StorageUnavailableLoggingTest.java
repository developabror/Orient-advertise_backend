package uz.orientadvertise.services.api.advice;

import java.util.concurrent.Executor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import uz.orientadvertise.services.common.exception.StorageUnavailableException;
import uz.orientadvertise.services.domain.notification.TelegramNotifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * How a storage 503 is LOGGED, which during an outage is an alerting question, not a logging one.
 *
 * <p>Until v1.0.144 this handler logged ERROR with the per-request correlation id inside the
 * message text. {@code TelegramLogForwarder} renders the formatted message into the chat message
 * and {@code TelegramRateLimiter} keys its 5-per-5-minutes window on a SHA-256 of exactly that
 * text — so every occurrence hashed differently, the per-message fold never applied, and only the
 * global 30-per-minute cap stood between a fleet-wide outage and an alert channel full of
 * identical 503s. The appender buffer holds 500 events and drops the oldest, so the WARNs that
 * explain the outage would be evicted by the WARNs caused by it.
 */
@SuppressWarnings("unchecked")
class StorageUnavailableLoggingTest {

    private GlobalExceptionHandler handler;
    private ch.qos.logback.classic.Logger handlerLogger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(mock(ObjectProvider.class), mock(ObjectProvider.class));
        handlerLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logs = new ListAppender<>();
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logs);
    }

    private java.util.List<ILoggingEvent> at(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    @Test
    void twoFailures_produceIdenticalWarnText_soTheRateLimiterCanFoldThem() {
        handler.handleStorageUnavailable(new StorageUnavailableException("Object storage is temporarily unavailable"));
        handler.handleStorageUnavailable(new StorageUnavailableException("Object storage is temporarily unavailable"));

        var warns = at(Level.WARN);
        assertEquals(2, warns.size());
        assertEquals(warns.get(0).getFormattedMessage(), warns.get(1).getFormattedMessage(),
                "the alert text must be byte-identical or the per-hash rate-limit window never folds");
        assertEquals(GlobalExceptionHandler.STORAGE_UNAVAILABLE_ALERT, warns.get(0).getFormattedMessage());
    }

    @Test
    void theWarnTextCarriesNoPerRequestValues() {
        var response = handler.handleStorageUnavailable(
                new StorageUnavailableException("Object stat failed: connect to minio:9000 refused"));

        String warn = at(Level.WARN).get(0).getFormattedMessage();
        String correlationId = response.getBody().correlationId();
        assertFalse(warn.contains(correlationId), warn);
        assertFalse(warn.contains("minio:9000"), warn);
        assertTrue(warn.contains("/api/health"), "point the operator at the authoritative signal");
    }

    @Test
    void theCorrelationIdStaysGreppable_onAnInfoLineBelowTheTelegramThreshold() {
        // INFO is under the appender's WARN floor, so it never reaches Telegram and never counts
        // against any alert budget — while the id the caller was handed stays findable in the log.
        var response = handler.handleStorageUnavailable(new StorageUnavailableException("boom"));

        var infos = at(Level.INFO);
        assertEquals(1, infos.size());
        assertTrue(infos.get(0).getFormattedMessage().contains(response.getBody().correlationId()),
                infos.get(0).getFormattedMessage());
        assertTrue(infos.get(0).getFormattedMessage().contains("boom"));
    }

    @Test
    void nothingIsLoggedAtError_soTheAlertBudgetIsNotBurnedByClientReachablePaths() {
        handler.handleStorageUnavailable(new StorageUnavailableException("x"));

        assertTrue(at(Level.ERROR).isEmpty(),
                "a device can trigger this every sync interval; ERROR is for what nobody can trigger");
    }

    @Test
    void theResponseIsStill503_withAUniqueCorrelationIdPerCall() {
        var first = handler.handleStorageUnavailable(new StorageUnavailableException("x"));
        var second = handler.handleStorageUnavailable(new StorageUnavailableException("x"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, first.getStatusCode());
        assertEquals(503, first.getBody().status());
        assertEquals("Service Unavailable", first.getBody().error());
        assertNotEquals(first.getBody().correlationId(), second.getBody().correlationId(),
                "the id is still per-request — it just isn't in the alert text any more");
    }
}
