package uz.orientadvertise.services.domain.model;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code Device.recordRemoteCapability} follows the {@code recordReportedVolume} contract: it
 * normalises, clamps, tolerates nulls per-field, and can never throw — because a malformed
 * capability block must never be able to fail a heartbeat.
 */
class DeviceRemoteCapabilityTest {

    private static Device newDevice() {
        return new Device(null, null, "SN-1", "TV-1");
    }

    // ----- positive -----

    @Test
    void record_fullBlock_storesEverythingAndStampsTime() {
        var device = newDevice();
        var at = Instant.parse("2026-08-27T10:12:00Z");

        device.recordRemoteCapability(true, "ROOT", "SCRCPY_WS", 1280, 720, at);

        assertEquals(Boolean.TRUE, device.getRemoteSupported());
        assertEquals("ROOT", device.getRemoteInput());
        assertEquals("SCRCPY_WS", device.getRemoteTransport());
        assertEquals(1280, device.getRemoteMaxWidth());
        assertEquals(720, device.getRemoteMaxHeight());
        assertEquals(at, device.getRemoteCapsAt());
    }

    @Test
    void record_lowercaseAndPaddedTokens_areNormalisedToUpperTrimmed() {
        var device = newDevice();

        device.recordRemoteCapability(true, "  root ", " scrcpy_ws", 1280, 720, null);

        assertEquals("ROOT", device.getRemoteInput());
        assertEquals("SCRCPY_WS", device.getRemoteTransport());
    }

    @Test
    void record_nullAt_stampsNow() {
        var device = newDevice();
        var before = Instant.now();

        device.recordRemoteCapability(false, null, null, null, null, null);

        assertTrue(!device.getRemoteCapsAt().isBefore(before));
        assertEquals(Boolean.FALSE, device.getRemoteSupported());
    }

    @Test
    void record_supportedFlipsTrueToFalse() {
        var device = newDevice();
        device.recordRemoteCapability(true, "ROOT", "SCRCPY_WS", 1280, 720, null);

        device.recordRemoteCapability(false, null, null, null, null, null);

        assertEquals(Boolean.FALSE, device.getRemoteSupported());
        assertEquals("ROOT", device.getRemoteInput(), "a null field must not wipe what we know");
    }

    // ----- negative / defensive -----

    @Test
    void record_allNulls_isACompleteNoOp() {
        var device = newDevice();

        device.recordRemoteCapability(null, null, null, null, null, null);

        assertNull(device.getRemoteSupported());
        assertNull(device.getRemoteCapsAt(), "an empty report must not even stamp a timestamp");
    }

    @Test
    void record_partialBlock_leavesUnreportedFieldsUntouched() {
        var device = newDevice();
        device.recordRemoteCapability(true, "ROOT", "SCRCPY_WS", 1280, 720, null);

        device.recordRemoteCapability(null, null, null, 1920, null, null);

        assertEquals(Boolean.TRUE, device.getRemoteSupported());
        assertEquals("ROOT", device.getRemoteInput());
        assertEquals(1920, device.getRemoteMaxWidth());
        assertEquals(720, device.getRemoteMaxHeight());
    }

    @Test
    void record_blankToken_storedAsNullNotEmptyString() {
        var device = newDevice();

        device.recordRemoteCapability(true, "   ", "", 1280, 720, null);

        assertNull(device.getRemoteInput());
        assertNull(device.getRemoteTransport());
    }

    @Test
    void record_overlongToken_truncatedToColumnWidth() {
        var device = newDevice();

        device.recordRemoteCapability(true, "A".repeat(50), "B".repeat(50), 1280, 720, null);

        assertEquals(16, device.getRemoteInput().length(), "remote_input is VARCHAR(16) in V43");
        assertEquals(24, device.getRemoteTransport().length(), "remote_transport is VARCHAR(24)");
    }

    @Test
    void record_nonPositiveOrAbsurdDimensions_areDropped() {
        var device = newDevice();

        device.recordRemoteCapability(true, "ROOT", "SCRCPY_WS", 0, -1, null);
        assertNull(device.getRemoteMaxWidth());
        assertNull(device.getRemoteMaxHeight());

        device.recordRemoteCapability(true, "ROOT", "SCRCPY_WS", 99_999, 99_999, null);
        assertNull(device.getRemoteMaxWidth());
        assertNull(device.getRemoteMaxHeight());
    }
}
