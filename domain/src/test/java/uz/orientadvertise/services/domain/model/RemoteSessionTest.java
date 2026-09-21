package uz.orientadvertise.services.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.RemoteSession.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session state machine is guarded in the ENTITY, not the service, so no caller — a new
 * controller, a background job, a future bulk operation — can write an illegal transition to the
 * database. These tests pin every legal move and every rejected one.
 */
class RemoteSessionTest {

    private static RemoteSession newSession() {
        return new RemoteSession("rs_deadbeef", null, false, "admin",
                Instant.now().plus(30, ChronoUnit.MINUTES));
    }

    // ----- positive: construction and the legal transitions -----

    @Test
    void newSession_startsPendingAndNonTerminal() {
        var session = newSession();

        assertEquals(Status.PENDING, session.getStatus());
        assertFalse(session.isTerminal());
        assertNotNull(session.getIssuedAt());
        assertNotNull(session.getUpdatedAt());
        assertNull(session.getStartedAt());
        assertNull(session.getEndedAt());
    }

    @Test
    void markActive_pendingToActive_setsStartedAtAndDimensions() {
        var session = newSession();

        session.markActive(1280, 720);

        assertEquals(Status.ACTIVE, session.getStatus());
        assertFalse(session.isTerminal());
        assertNotNull(session.getStartedAt());
        assertEquals(1280, session.getDeviceWidth());
        assertEquals(720, session.getDeviceHeight());
    }

    @Test
    void markActive_repeatAckWhileActive_isIdempotentAndKeepsFirstStartedAt() {
        // A device retrying its READY ack after a lost response must not be punished with a 409.
        var session = newSession();
        session.markActive(1280, 720);
        var firstStart = session.getStartedAt();

        session.markActive(1920, 1080);

        assertEquals(Status.ACTIVE, session.getStatus());
        assertEquals(firstStart, session.getStartedAt(), "started_at anchors on the FIRST ready ack");
        assertEquals(1920, session.getDeviceWidth());
    }

    @Test
    void markActive_nonPositiveDimensions_storedAsNullNotNonsense() {
        var session = newSession();

        session.markActive(0, -5);

        assertEquals(Status.ACTIVE, session.getStatus());
        assertNull(session.getDeviceWidth());
        assertNull(session.getDeviceHeight());
    }

    @Test
    void markEnded_fromPending_isTerminalWithReason() {
        var session = newSession();

        session.markEnded(RemoteSession.END_REASON_OPERATOR_STOP);

        assertEquals(Status.ENDED, session.getStatus());
        assertTrue(session.isTerminal());
        assertEquals(RemoteSession.END_REASON_OPERATOR_STOP, session.getEndReason());
        assertNotNull(session.getEndedAt());
    }

    @Test
    void markEnded_fromActive_isAllowed() {
        var session = newSession();
        session.markActive(1280, 720);

        session.markEnded(RemoteSession.END_REASON_DEVICE_ENDED);

        assertEquals(Status.ENDED, session.getStatus());
    }

    @Test
    void markEnded_blankReason_storedAsNullNotEmptyString() {
        var session = newSession();

        session.markEnded("   ");

        assertNull(session.getEndReason());
    }

    @Test
    void markEnded_overlongReason_truncatedToColumnWidth() {
        var session = newSession();

        session.markEnded("X".repeat(200));

        assertEquals(64, session.getEndReason().length(), "end_reason is VARCHAR(64) in V43");
    }

    @Test
    void markFailed_recordsErrorAndDeviceFailedReason() {
        var session = newSession();

        session.markFailed("su: not found");

        assertEquals(Status.FAILED, session.getStatus());
        assertTrue(session.isTerminal());
        assertEquals("su: not found", session.getError());
        assertEquals(RemoteSession.END_REASON_DEVICE_FAILED, session.getEndReason());
    }

    @Test
    void markExpired_recordsExpiredReason() {
        var session = newSession();

        session.markExpired();

        assertEquals(Status.EXPIRED, session.getStatus());
        assertTrue(session.isTerminal());
        assertEquals(RemoteSession.END_REASON_EXPIRED, session.getEndReason());
    }

    @Test
    void isPastExpiry_comparesAgainstTheHardCeiling() {
        var session = new RemoteSession("rs_1", null, false, "admin",
                Instant.now().plus(5, ChronoUnit.MINUTES));

        assertFalse(session.isPastExpiry(Instant.now()));
        assertTrue(session.isPastExpiry(Instant.now().plus(6, ChronoUnit.MINUTES)));
    }

    // ----- negative: every move out of a terminal state is rejected -----

    @Test
    void terminalSession_cannotBeActivated() {
        var ended = newSession();
        ended.markEnded(RemoteSession.END_REASON_OPERATOR_STOP);

        var e = assertThrows(IllegalStateException.class, () -> ended.markActive(1280, 720));
        assertTrue(e.getMessage().contains("ENDED"), "message must name the blocking state");
        assertEquals(Status.ENDED, ended.getStatus(), "a rejected transition must not mutate state");
    }

    @Test
    void terminalSession_cannotBeEndedTwice() {
        var ended = newSession();
        ended.markEnded(RemoteSession.END_REASON_OPERATOR_STOP);

        assertThrows(IllegalStateException.class,
                () -> ended.markEnded(RemoteSession.END_REASON_DEVICE_ENDED));
    }

    @Test
    void failedSession_cannotBeEndedOrExpiredOrReactivated() {
        var failed = newSession();
        failed.markFailed("boom");

        assertThrows(IllegalStateException.class, () -> failed.markEnded("x"));
        assertThrows(IllegalStateException.class, failed::markExpired);
        assertThrows(IllegalStateException.class, () -> failed.markActive(1, 1));
        assertEquals(Status.FAILED, failed.getStatus());
    }

    @Test
    void expiredSession_cannotBeReactivatedOrFailed() {
        var expired = newSession();
        expired.markExpired();

        assertThrows(IllegalStateException.class, () -> expired.markActive(1280, 720));
        assertThrows(IllegalStateException.class, () -> expired.markFailed("late"));
        assertEquals(Status.EXPIRED, expired.getStatus());
    }
}
