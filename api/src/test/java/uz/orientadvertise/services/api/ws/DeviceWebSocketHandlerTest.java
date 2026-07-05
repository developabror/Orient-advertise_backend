package uz.orientadvertise.services.api.ws;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import uz.orientadvertise.services.api.ws.DeviceWebSocketHandler;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RemoteActionRepository;
import uz.orientadvertise.services.service.ContentVersionService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceWebSocketHandlerTest {

    private RemoteActionRepository remoteActionRepository;
    private DeviceRepository deviceRepository;
    private ContentVersionService versionService;
    private DeviceWebSocketHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        remoteActionRepository = mock(RemoteActionRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        versionService = mock(ContentVersionService.class);

        ObjectProvider<RemoteActionRepository> raProvider = mock(ObjectProvider.class);
        when(raProvider.getIfAvailable()).thenReturn(remoteActionRepository);
        ObjectProvider<DeviceRepository> drProvider = mock(ObjectProvider.class);
        when(drProvider.getIfAvailable()).thenReturn(deviceRepository);
        ObjectProvider<ContentVersionService> vsProvider = mock(ObjectProvider.class);
        when(vsProvider.getIfAvailable()).thenReturn(versionService);

        handler = new DeviceWebSocketHandler(raProvider, drProvider, vsProvider);
    }

    @Test
    void connect_withPathDeviceId_registersSession() {
        var session = newSession("s1", "/ws/devices/42");
        when(remoteActionRepository.findPendingByDevice(42L)).thenReturn(List.of());
        when(deviceRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

        handler.afterConnectionEstablished(session);

        assertTrue(handler.isConnected(42L));
        assertEquals(1, handler.connectedDeviceCount());
    }

    @Test
    void connect_invalidPath_closesSession() throws IOException {
        var session = newSession("bad", "/ws/devices/notanumber");

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.BAD_DATA);
        assertFalse(handler.isConnected(0L));
    }

    @Test
    void connect_secondSessionForSameDevice_closesPrior() throws IOException {
        var first = newSession("first", "/ws/devices/7");
        when(remoteActionRepository.findPendingByDevice(anyLong())).thenReturn(List.of());
        when(deviceRepository.findByIdAndDeletedAtIsNull(anyLong())).thenReturn(Optional.empty());

        handler.afterConnectionEstablished(first);
        var second = newSession("second", "/ws/devices/7");
        handler.afterConnectionEstablished(second);

        verify(first).close(argThat(s -> s.getCode() == CloseStatus.POLICY_VIOLATION.getCode()));
        // The map points at the new session
        assertTrue(handler.isConnected(7L));
    }

    @Test
    void disconnect_removesFromMap() {
        var session = newSession("s2", "/ws/devices/8");
        when(remoteActionRepository.findPendingByDevice(anyLong())).thenReturn(List.of());
        when(deviceRepository.findByIdAndDeletedAtIsNull(anyLong())).thenReturn(Optional.empty());
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertFalse(handler.isConnected(8L));
    }

    @Test
    void connect_replaysPendingActions() throws IOException {
        var session = newSession("s3", "/ws/devices/100");
        var action = mock(RemoteAction.class);
        when(action.getId()).thenReturn(99L);
        when(action.getActionType()).thenReturn("REBOOT");
        when(action.getIssuedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(remoteActionRepository.findPendingByDevice(100L)).thenReturn(List.of(action));
        when(deviceRepository.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.empty());

        handler.afterConnectionEstablished(session);

        verify(session, atLeastOnce()).sendMessage(argThat((TextMessage m) ->
                m.getPayload().contains("ACTION_PENDING") && m.getPayload().contains("99")));
    }

    @Test
    void connect_noVersionMismatch_noSyncRequiredSent() throws IOException {
        var session = newSession("s4", "/ws/devices/200");
        var device = mock(Device.class);
        when(device.getCurrentContentVersion()).thenReturn("hash-A");
        when(remoteActionRepository.findPendingByDevice(200L)).thenReturn(List.of());
        when(deviceRepository.findByIdAndDeletedAtIsNull(200L)).thenReturn(Optional.of(device));
        when(versionService.computeExpectedVersion(any(), any())).thenReturn("hash-A");

        handler.afterConnectionEstablished(session);

        verify(session, never()).sendMessage(argThat((TextMessage m) ->
                m.getPayload().contains("SYNC_REQUIRED")));
    }

    @Test
    void connect_versionMismatch_sendsSyncRequired() throws IOException {
        var session = newSession("s5", "/ws/devices/300");
        var device = mock(Device.class);
        when(device.getCurrentContentVersion()).thenReturn("old-hash");
        when(remoteActionRepository.findPendingByDevice(300L)).thenReturn(List.of());
        when(deviceRepository.findByIdAndDeletedAtIsNull(300L)).thenReturn(Optional.of(device));
        when(versionService.computeExpectedVersion(any(), any())).thenReturn("new-hash");

        handler.afterConnectionEstablished(session);

        verify(session, times(1)).sendMessage(argThat((TextMessage m) ->
                m.getPayload().contains("SYNC_REQUIRED") && m.getPayload().contains("new-hash")));
    }

    @Test
    void pushToDevices_skippedWhenNotConnected() {
        var result = handler.pushToDevices(List.of(999L), "{}");
        assertEquals(0, result.sent());
        assertEquals(1, result.skipped());
    }

    private WebSocketSession newSession(String id, String path) {
        var session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getUri()).thenReturn(URI.create("ws://test" + path));
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
