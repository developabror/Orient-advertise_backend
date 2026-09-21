package uz.orientadvertise.services.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.domain.content.DevicePushChannel;
import uz.orientadvertise.services.domain.content.PushMessageType;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.RemoteSession;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RemoteSessionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drift guard for the two remote-session push types.
 *
 * <p>{@code BatchedSyncDispatcher} emits a hard-coded {@code "SYNC_CONTENT"} that is not a member
 * of {@link PushMessageType} — a real, pre-existing bug that makes the enum decorative for that
 * message. This test exists so the same thing can never happen to
 * {@code REMOTE_SESSION_START}/{@code REMOTE_SESSION_STOP}: the expected wire value is read
 * <b>from the enum</b>, never written as a literal, so renaming a constant without updating the
 * emitter fails here instead of silently shipping an unrecognised frame to every TV-Box.
 */
class PushMessageTypeWireTest {

    private static final Long DEVICE_ID = 12L;

    private RemoteSessionRepository sessionRepository;
    private DeviceRepository deviceRepository;
    private DevicePushChannel pushChannel;
    private RemoteSessionService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(RemoteSessionRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        pushChannel = mock(DevicePushChannel.class);

        var properties = new RemoteProperties();
        properties.setEnabled(true);
        properties.getRelay().setSigningSecret("0123456789abcdef0123456789abcdef");
        properties.getRelay().setAgentUrl("wss://relay.test/agent");
        properties.getRelay().setViewerUrl("wss://relay.test/viewer");

        service = new RemoteSessionService(sessionRepository, deviceRepository,
                new RemoteSessionTicketService(properties), pushChannel,
                mock(EntityAuditService.class), properties);
    }

    private Device device() {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(DEVICE_ID);
        when(device.getName()).thenReturn("TV-1");
        when(device.getRemoteSupported()).thenReturn(true);
        when(device.getRemoteMaxWidth()).thenReturn(null);
        return device;
    }

    /** Pull the {@code "type":"…"} value out of an emitted frame. */
    private static String typeOf(String frame) {
        var marker = "\"type\":\"";
        int start = frame.indexOf(marker);
        assertTrue(start >= 0, "frame carries no type discriminator: " + frame);
        start += marker.length();
        return frame.substring(start, frame.indexOf('"', start));
    }

    private String captureStartFrame() {
        var device = device();
        when(deviceRepository.findByIdAndDeletedAtIsNull(DEVICE_ID)).thenReturn(Optional.of(device));
        when(sessionRepository.findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), any()))
                .thenReturn(Optional.empty());
        when(sessionRepository.save(any(RemoteSession.class))).thenAnswer(inv -> inv.getArgument(0));

        service.start(DEVICE_ID, false, "operator1");

        return lastPushedFrame();
    }

    private String captureStopFrame() {
        var device = device();
        var session = new RemoteSession("rs_abc123", device, false, "operator1",
                Instant.now().plus(30, ChronoUnit.MINUTES));
        session.markActive(1280, 720);
        when(sessionRepository.findBySessionKey("rs_abc123")).thenReturn(Optional.of(session));

        service.stop(DEVICE_ID, "rs_abc123", "operator1");

        return lastPushedFrame();
    }

    /**
     * The most recent frame pushed to the device. Uses {@code atLeastOnce} so a single test may
     * drive more than one lifecycle event against the same mock.
     */
    private String lastPushedFrame() {
        var frame = ArgumentCaptor.forClass(String.class);
        verify(pushChannel, org.mockito.Mockito.atLeastOnce()).push(eq(DEVICE_ID), frame.capture());
        return frame.getAllValues().getLast();
    }

    // ----- the guard itself -----

    @Test
    void startFrame_typeEqualsTheEnumConstantName() {
        assertEquals(PushMessageType.REMOTE_SESSION_START.name(), typeOf(captureStartFrame()));
    }

    @Test
    void stopFrame_typeEqualsTheEnumConstantName() {
        assertEquals(PushMessageType.REMOTE_SESSION_STOP.name(), typeOf(captureStopFrame()));
    }

    @Test
    void bothTypes_resolveBackToRealEnumConstants() {
        // The inverse check: whatever string went on the wire must round-trip through valueOf.
        assertEquals(PushMessageType.REMOTE_SESSION_START,
                PushMessageType.valueOf(typeOf(captureStartFrame())));
        assertEquals(PushMessageType.REMOTE_SESSION_STOP,
                PushMessageType.valueOf(typeOf(captureStopFrame())));
    }

    // ----- frame shape (REMOTE_CONTROL_CONTRACT §4.1) -----

    @Test
    void startFrame_carriesTheFullAgentRendezvous() {
        var frame = captureStartFrame();

        assertTrue(frame.contains("\"sessionId\":\"rs_"), frame);
        assertTrue(frame.contains("\"relayUrl\":\"wss://relay.test/agent\""), frame);
        assertTrue(frame.contains("\"agentTicket\":\""), frame);
        assertTrue(frame.contains("\"expiresAt\":\""), frame);
        assertTrue(frame.contains("\"viewOnly\":false"), frame);
        assertTrue(frame.contains("\"maxWidth\":1280"), frame);
        assertTrue(frame.contains("\"maxFps\":15"), frame);
        assertTrue(frame.contains("\"bitRate\":2000000"), frame);
    }

    @Test
    void startFrame_sendsTheAgentTicketNeverTheViewerTicket() {
        // A device that could replay the viewer ticket would defeat the whole two-ticket split.
        var device = device();
        when(deviceRepository.findByIdAndDeletedAtIsNull(DEVICE_ID)).thenReturn(Optional.of(device));
        when(sessionRepository.findFirstByDeviceIdAndStatusIn(eq(DEVICE_ID), any()))
                .thenReturn(Optional.empty());
        when(sessionRepository.save(any(RemoteSession.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.start(DEVICE_ID, false, "operator1");

        var frame = ArgumentCaptor.forClass(String.class);
        verify(pushChannel).push(eq(DEVICE_ID), frame.capture());
        assertNotNull(view.viewerTicket());
        assertTrue(!frame.getValue().contains(view.viewerTicket()),
                "the device push must never carry the viewer's ticket");
    }

    @Test
    void stopFrame_carriesOnlyTheTypeAndSessionId() {
        var frame = captureStopFrame();

        assertTrue(frame.contains("\"sessionId\":\"rs_abc123\""), frame);
        assertTrue(!frame.contains("Ticket"), "a teardown needs no credential: " + frame);
    }

    @Test
    void noFrameEverCarriesTheSigningSecret() {
        assertTrue(!captureStartFrame().contains("0123456789abcdef0123456789abcdef"));
    }

    @Test
    void pushIsAttemptedExactlyOncePerLifecycleEvent() {
        captureStartFrame();
        verify(pushChannel, org.mockito.Mockito.times(1)).push(eq(DEVICE_ID), anyString());
    }
}
