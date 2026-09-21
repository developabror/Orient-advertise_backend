package uz.orientadvertise.services.infra.storage;

import java.time.Duration;
import java.time.Instant;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The state machine behind "is object storage reachable".
 *
 * <p>Pins the two properties the v1.0.144 fix depends on and that the old two-line flag had
 * neither of: a transition logs <b>once</b> (a fleet of TV boxes reaches {@code markDegraded} on
 * every failed call, and WARN is forwarded to the operator Telegram chat), and {@code since} marks
 * when the state was <b>entered</b>, not when it was last re-asserted — otherwise "storage has
 * been down for 4 hours" reads as "down for 30 seconds" forever.
 */
class MinioHealthStatusTest {

    private MinioHealthStatus status;
    private ch.qos.logback.classic.Logger statusLogger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        status = new MinioHealthStatus();
        statusLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MinioHealthStatus.class);
        logs = new ListAppender<>();
        logs.start();
        statusLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        statusLogger.detachAppender(logs);
    }

    private long countAt(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).count();
    }

    @Test
    void startsDegraded_soAPreStartupUploadCannot503Silently() {
        assertFalse(status.isAvailable());
        assertEquals(MinioHealthStatus.State.DEGRADED, status.getState());
        assertEquals(MinioHealthStatus.NOT_PROBED, status.getReason());
        assertNotNull(status.getSince());
    }

    @Test
    void markUp_transitionsAndLogsRecoveryOnce() {
        status.markUp();   // leave the initial "never probed" state first
        status.markDegraded("upload: ConnectException");
        logs.list.clear();

        status.markUp();

        assertTrue(status.isAvailable());
        assertEquals(MinioHealthStatus.State.UP, status.getState());
        assertEquals(1, countAt(Level.INFO), "the recovery is announced exactly once");
        // The recovery line must carry BOTH what broke and for how long — an operator reading
        // "UP again" with no duration cannot tell a blip from a four-hour outage.
        String line = logs.list.get(0).getFormattedMessage();
        assertTrue(line.contains("upload: ConnectException"), line);
        assertTrue(line.contains("after"), line);
    }

    @Test
    void markDegraded_recordsReasonAndSince_andWarnsOnce() {
        status.markUp();
        logs.list.clear();
        Instant before = Instant.now();

        status.markDegraded("download: SocketTimeoutException");

        assertFalse(status.isAvailable());
        assertEquals("download: SocketTimeoutException", status.getReason());
        assertFalse(status.getSince().isBefore(before), "since marks when DEGRADED was entered");
        assertEquals(1, countAt(Level.WARN));
        assertTrue(logs.list.get(0).getFormattedMessage().contains("download: SocketTimeoutException"));
    }

    @Test
    void reMarkingDegraded_doesNotResetSince_andDoesNotLogAgain() throws Exception {
        status.markUp();
        status.markDegraded("upload: ConnectException");
        Instant firstSince = status.getSince();
        logs.list.clear();

        Thread.sleep(5);
        status.markDegraded("download: ConnectException");
        status.markDegraded("object stat: ConnectException");

        assertSame(firstSince, status.getSince(),
                "since must anchor to the FIRST failure of this outage, not the latest one");
        assertEquals("upload: ConnectException", status.getReason(),
                "the reason stays the one that opened the outage");
        assertEquals(0, logs.list.size(),
                "every device in the fleet reaches this path — only the transition may log");
    }

    @Test
    void reMarkingUp_doesNotResetSince_andDoesNotLogAgain() throws Exception {
        status.markUp();
        Instant firstSince = status.getSince();
        logs.list.clear();

        Thread.sleep(5);
        status.markUp();
        status.markUp();

        assertSame(firstSince, status.getSince());
        assertEquals(0, logs.list.size(), "a healthy probe every 30s must not log every 30s");
    }

    @Test
    void fullOutageCycle_degradeThenRecover_logsExactlyTwoLines() {
        status.markUp();
        logs.list.clear();

        status.markDegraded("upload: ConnectException");
        status.markDegraded("upload: ConnectException");
        status.markUp();
        status.markUp();

        assertEquals(1, countAt(Level.WARN));
        assertEquals(1, countAt(Level.INFO));
        assertTrue(status.isAvailable());
        assertEquals("available", status.getReason());
    }

    @Test
    void blankReason_fallsBackToAPlaceholder_soHealthNeverPublishesNull() {
        status.markUp();

        status.markDegraded("   ");

        assertEquals("unspecified storage failure", status.getReason());
        assertFalse(status.isAvailable());
    }

    @Test
    void nullReason_fallsBackToAPlaceholder() {
        status.markUp();

        status.markDegraded(null);

        assertEquals("unspecified storage failure", status.getReason());
    }

    @Test
    void humanize_rendersSecondsMinutesAndHours() {
        assertEquals("42s", MinioHealthStatus.humanize(Duration.ofSeconds(42)));
        assertEquals("3m12s", MinioHealthStatus.humanize(Duration.ofSeconds(192)));
        assertEquals("2h05m", MinioHealthStatus.humanize(Duration.ofMinutes(125)));
        // A clock stepping backwards must not print a negative outage duration.
        assertEquals("0s", MinioHealthStatus.humanize(Duration.ofSeconds(-5)));
    }
}
