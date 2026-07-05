package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceStatusView;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.DeviceStatusViewRepository;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.domain.repository.RemoteActionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceDiagnosticsServiceTest {

    private DeviceRepository deviceRepository;
    private DeviceStatusViewRepository statusViewRepository;
    private EventRepository eventRepository;
    private RemoteActionRepository remoteActionRepository;
    private DeviceDiagnosticsService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        statusViewRepository = mock(DeviceStatusViewRepository.class);
        eventRepository = mock(EventRepository.class);
        remoteActionRepository = mock(RemoteActionRepository.class);
        service = new DeviceDiagnosticsService(deviceRepository, statusViewRepository,
                eventRepository, remoteActionRepository);
    }

    @Test
    void unknownDevice_throws404() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getDiagnostics(404L));
    }

    @Test
    void neverHeartbeated_returnsEmptyEnvelope_notError() {
        // Edge case requirement: device with no heartbeat data must NOT throw — operator
        // console renders "no data yet" rather than an error.
        var device = baseDevice(1L);
        when(device.getLastHeartbeatAt()).thenReturn(null);
        when(device.getCurrentContentVersion()).thenReturn(null);
        when(device.getLastKnownIp()).thenReturn(null);
        when(deviceRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(device));
        when(eventRepository.findByDeviceIdOrderByOccurredAtDesc(1L)).thenReturn(List.of());
        when(remoteActionRepository.findByDeviceIdOrderByIssuedAtDesc(1L)).thenReturn(List.of());
        when(remoteActionRepository.countPendingByDevice(1L)).thenReturn(0L);

        var view = service.getDiagnostics(1L);

        assertEquals(1L, view.deviceId());
        assertNull(view.lastHeartbeatAt());
        assertNull(view.currentContentVersion());
        assertNull(view.lastKnownIp());
        assertEquals(0, view.pendingActionCount());
        assertTrue(view.recentEvents().isEmpty());
        assertTrue(view.recentActions().isEmpty());
    }

    @Test
    void liveDevice_returnsCompleteEnvelope() {
        var device = baseDevice(2L);
        when(device.getLastHeartbeatAt()).thenReturn(Instant.parse("2026-05-06T01:00:00Z"));
        when(device.getCurrentContentVersion()).thenReturn("v-abc");
        when(device.getLastKnownIp()).thenReturn("10.0.0.42");
        when(deviceRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(device));

        var event = mock(Event.class);
        when(event.getId()).thenReturn(50L);
        when(event.getEventType()).thenReturn("DEVICE_OFFLINE");
        when(event.getPriority()).thenReturn(Event.Priority.CRITICAL);
        when(event.getOccurredAt()).thenReturn(Instant.parse("2026-05-06T00:50:00Z"));
        when(eventRepository.findByDeviceIdOrderByOccurredAtDesc(2L)).thenReturn(List.of(event));

        var action = mock(RemoteAction.class);
        when(action.getId()).thenReturn(60L);
        when(action.getActionType()).thenReturn("REBOOT");
        when(action.getStatus()).thenReturn(RemoteAction.Status.PENDING);
        when(action.getIssuedAt()).thenReturn(Instant.parse("2026-05-06T00:55:00Z"));
        when(action.getExpiresAt()).thenReturn(Instant.parse("2026-05-06T01:05:00Z"));
        when(remoteActionRepository.findByDeviceIdOrderByIssuedAtDesc(2L)).thenReturn(List.of(action));
        when(remoteActionRepository.countPendingByDevice(2L)).thenReturn(3L);

        var view = service.getDiagnostics(2L);

        assertEquals("v-abc", view.currentContentVersion());
        assertEquals("10.0.0.42", view.lastKnownIp());
        assertEquals(3, view.pendingActionCount());
        assertEquals(1, view.recentEvents().size());
        assertEquals(50L, view.recentEvents().get(0).id());
        assertEquals("CRITICAL", view.recentEvents().get(0).priority());
        assertEquals(1, view.recentActions().size());
        assertEquals(60L, view.recentActions().get(0).id());
    }

    @Test
    void recentEvents_cappedAt10() {
        var device = baseDevice(3L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(device));

        // 25 events available — only 10 returned in the view.
        var manyEvents = new ArrayList<Event>();
        for (int i = 0; i < 25; i++) {
            var e = mock(Event.class);
            when(e.getId()).thenReturn((long) i);
            when(e.getEventType()).thenReturn("X");
            when(e.getPriority()).thenReturn(Event.Priority.INFO);
            when(e.getOccurredAt()).thenReturn(Instant.now());
            manyEvents.add(e);
        }
        when(eventRepository.findByDeviceIdOrderByOccurredAtDesc(3L)).thenReturn(manyEvents);
        when(remoteActionRepository.findByDeviceIdOrderByIssuedAtDesc(3L)).thenReturn(List.of());
        when(remoteActionRepository.countPendingByDevice(any())).thenReturn(0L);

        var view = service.getDiagnostics(3L);

        assertEquals(10, view.recentEvents().size());
    }

    @Test
    void recentActions_cappedAt5() {
        var device = baseDevice(4L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(device));

        var manyActions = new ArrayList<RemoteAction>();
        for (int i = 0; i < 12; i++) {
            var a = mock(RemoteAction.class);
            when(a.getId()).thenReturn((long) i);
            when(a.getActionType()).thenReturn("REBOOT");
            when(a.getStatus()).thenReturn(RemoteAction.Status.CONFIRMED);
            when(a.getIssuedAt()).thenReturn(Instant.now());
            when(a.getExpiresAt()).thenReturn(Instant.now().plusSeconds(60));
            manyActions.add(a);
        }
        when(eventRepository.findByDeviceIdOrderByOccurredAtDesc(4L)).thenReturn(List.of());
        when(remoteActionRepository.findByDeviceIdOrderByIssuedAtDesc(4L)).thenReturn(manyActions);
        when(remoteActionRepository.countPendingByDevice(any())).thenReturn(0L);

        var view = service.getDiagnostics(4L);

        assertEquals(5, view.recentActions().size());
    }

    @Test
    void pendingActionCount_isFromDedicatedQuery_notListSize() {
        // recentActions is capped at 5, but pendingActionCount comes from countPendingByDevice
        // and reflects the true number of pending actions, even if more than 5 exist.
        var device = baseDevice(5L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(device));
        when(eventRepository.findByDeviceIdOrderByOccurredAtDesc(5L)).thenReturn(List.of());
        when(remoteActionRepository.findByDeviceIdOrderByIssuedAtDesc(5L)).thenReturn(List.of());
        when(remoteActionRepository.countPendingByDevice(5L)).thenReturn(8L);

        var view = service.getDiagnostics(5L);

        assertEquals(8, view.pendingActionCount());
    }

    @Test
    void status_derivedFromComputedView_notRawColumn() {
        // baseDevice's raw status is ONLINE, but the heartbeat-derived view says OFFLINE —
        // the diagnostics envelope must reflect the view (single source of truth).
        var device = baseDevice(7L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(device));
        when(eventRepository.findByDeviceIdOrderByOccurredAtDesc(7L)).thenReturn(List.of());
        when(remoteActionRepository.findByDeviceIdOrderByIssuedAtDesc(7L)).thenReturn(List.of());
        when(remoteActionRepository.countPendingByDevice(7L)).thenReturn(0L);
        var statusView = mock(DeviceStatusView.class);
        when(statusView.getComputedStatus()).thenReturn(Device.Status.OFFLINE);
        when(statusViewRepository.findById(7L)).thenReturn(Optional.of(statusView));

        var view = service.getDiagnostics(7L);

        assertEquals("OFFLINE", view.status());
    }

    private static Device baseDevice(Long id) {
        Device d = mock(Device.class);
        when(d.getId()).thenReturn(id);
        when(d.getSerialNumber()).thenReturn("SN-" + id);
        when(d.getName()).thenReturn("Device-" + id);
        when(d.getStatus()).thenReturn(Device.Status.ONLINE);
        return d;
    }
}
