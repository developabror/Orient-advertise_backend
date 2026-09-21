package uz.orientadvertise.services.domain.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceStatusEvaluatorTest {

    private final Instant now = Instant.parse("2025-06-15T12:00:00Z");

    @Test
    void neverHadHeartbeat_isOffline() {
        var status = DeviceStatusEvaluator.evaluate(null, true, now);
        assertEquals(Device.Status.OFFLINE, status);
    }

    @Test
    void recentHeartbeatWithContent_isOnline() {
        var lastHeartbeat = now.minus(2, ChronoUnit.MINUTES);
        var status = DeviceStatusEvaluator.evaluate(lastHeartbeat, true, now);
        assertEquals(Device.Status.ONLINE, status);
    }

    @Test
    void recentHeartbeatNoContent_isNoContent() {
        var lastHeartbeat = now.minus(2, ChronoUnit.MINUTES);
        var status = DeviceStatusEvaluator.evaluate(lastHeartbeat, false, now);
        assertEquals(Device.Status.NO_CONTENT, status);
    }

    @Test
    void heartbeat14MinutesAgo_isOnline() {
        var lastHeartbeat = now.minus(14, ChronoUnit.MINUTES);
        var status = DeviceStatusEvaluator.evaluate(lastHeartbeat, true, now);
        assertEquals(Device.Status.ONLINE, status);
    }

    @Test
    void heartbeatExactly15Minutes_isOnline() {
        var lastHeartbeat = now.minus(15, ChronoUnit.MINUTES);
        var status = DeviceStatusEvaluator.evaluate(lastHeartbeat, true, now);
        assertEquals(Device.Status.ONLINE, status, "At threshold but within grace");
    }

    @Test
    void heartbeat15MinutesPlus30Seconds_stillOnline_graceWindow() {
        var lastHeartbeat = now.minus(15, ChronoUnit.MINUTES).minus(30, ChronoUnit.SECONDS);
        var status = DeviceStatusEvaluator.evaluate(lastHeartbeat, true, now);
        assertEquals(Device.Status.ONLINE, status, "Within 60s grace window");
    }

    @Test
    void heartbeat15MinutesPlus59Seconds_stillOnline_atGraceBoundary() {
        var lastHeartbeat = now.minus(15, ChronoUnit.MINUTES).minus(59, ChronoUnit.SECONDS);
        var status = DeviceStatusEvaluator.evaluate(lastHeartbeat, true, now);
        assertEquals(Device.Status.ONLINE, status, "Just inside 60s grace");
    }

    @Test
    void heartbeat16Minutes1Second_isOffline_pastGrace() {
        var lastHeartbeat = now.minus(16, ChronoUnit.MINUTES).minus(1, ChronoUnit.SECONDS);
        var status = DeviceStatusEvaluator.evaluate(lastHeartbeat, true, now);
        assertEquals(Device.Status.OFFLINE, status, "Past 15min + 60s grace");
    }

    @Test
    void heartbeat1HourAgo_isOffline() {
        var lastHeartbeat = now.minus(1, ChronoUnit.HOURS);
        var status = DeviceStatusEvaluator.evaluate(lastHeartbeat, true, now);
        assertEquals(Device.Status.OFFLINE, status);
    }

    @Test
    void offlineTakesPriorityOverNoContent() {
        // Stale heartbeat AND no content → still OFFLINE (not NO_CONTENT)
        var lastHeartbeat = now.minus(30, ChronoUnit.MINUTES);
        var status = DeviceStatusEvaluator.evaluate(lastHeartbeat, false, now);
        assertEquals(Device.Status.OFFLINE, status);
    }

    @Test
    void isOffline_matchesEvaluate_onBothSidesOfTheGraceBoundary() {
        // The heartbeat derives "was this device showing OFFLINE?" from isOffline; it must agree
        // with evaluate() exactly, or a transition would be announced that no surface displayed.
        assertTrue(DeviceStatusEvaluator.isOffline(null, now));
        assertFalse(DeviceStatusEvaluator.isOffline(now.minus(15, ChronoUnit.MINUTES).minus(59, ChronoUnit.SECONDS), now));
        assertTrue(DeviceStatusEvaluator.isOffline(now.minus(16, ChronoUnit.MINUTES).minus(1, ChronoUnit.SECONDS), now));
    }
}
