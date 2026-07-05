package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.DeviceStatusPayload;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.Incident;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceHealthMonitorTest {

    private DeviceRepository deviceRepository;
    private IncidentRepository incidentRepository;
    private IncidentService incidentService;
    private DashboardService dashboardService;
    private DashboardEventBroadcaster dashboardBroadcaster;
    private DeviceHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        incidentRepository = mock(IncidentRepository.class);
        incidentService = mock(IncidentService.class);
        dashboardService = mock(DashboardService.class);
        dashboardBroadcaster = mock(DashboardEventBroadcaster.class);
        monitor = new DeviceHealthMonitor(deviceRepository, incidentRepository,
                incidentService, dashboardService, dashboardBroadcaster);
    }

    @Test
    void noStaleDevices_emitsNothing() {
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of());
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());

        var result = monitor.runHealthCheck();

        assertEquals(0, result.offlineEmitted());
        assertEquals(0, result.mismatchEmitted());
        verify(incidentService, never()).processEvent(any());
    }

    @Test
    void staleHeartbeat_emitsCriticalDeviceOfflineEvent() {
        Device d = staleHeartbeatDevice(1L, Duration.ofMinutes(20));
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of(d));
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());
        when(deviceRepository.findById(1L)).thenReturn(Optional.of(d));
        when(incidentRepository.findOpenByDeviceAndEventType(eq(1L), eq("DEVICE_OFFLINE")))
                .thenReturn(Optional.empty());

        var result = monitor.runHealthCheck();

        assertEquals(1, result.offlineEmitted());
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(incidentService).processEvent(captor.capture());
        assertEquals("DEVICE_OFFLINE", captor.getValue().getEventType());
        assertEquals(Event.Priority.CRITICAL, captor.getValue().getPriority());
    }

    @Test
    void staleContentMismatch_emitsMediumPriorityEvent() {
        Device d = mismatchDevice(2L, Duration.ofMinutes(45));
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of());
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of(d));
        when(deviceRepository.findById(2L)).thenReturn(Optional.of(d));
        when(incidentRepository.findOpenByDeviceAndEventType(eq(2L), eq("CONTENT_VERSION_MISMATCH")))
                .thenReturn(Optional.empty());

        var result = monitor.runHealthCheck();

        assertEquals(1, result.mismatchEmitted());
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(incidentService).processEvent(captor.capture());
        assertEquals("CONTENT_VERSION_MISMATCH", captor.getValue().getEventType());
        assertEquals(Event.Priority.MEDIUM, captor.getValue().getPriority());
    }

    @Test
    void existingOpenIncident_skipsEmission_avoidsDuplicates() {
        // Re-check incident status before opening — recovery edge case.
        Device d = staleHeartbeatDevice(3L, Duration.ofMinutes(60));
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of(d));
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(d));
        when(incidentRepository.findOpenByDeviceAndEventType(eq(3L), eq("DEVICE_OFFLINE")))
                .thenReturn(Optional.of(mock(Incident.class)));

        var result = monitor.runHealthCheck();

        assertEquals(0, result.offlineEmitted());
        assertEquals(1, result.offlineSkipped());
        verify(incidentService, never()).processEvent(any());
    }

    @Test
    void deviceRecoveredBetweenFetchAndDecision_skipsEmission() {
        // Candidate fetched (stale), but heartbeat arrived in the meantime — within the
        // monitor's per-device transaction the re-read sees the fresh state.
        Device stale = staleHeartbeatDevice(4L, Duration.ofMinutes(20));
        Device recovered = mock(Device.class);
        when(recovered.isDeleted()).thenReturn(false);
        // Recent heartbeat — 1 minute ago, well inside the 15-min threshold.
        when(recovered.getLastHeartbeatAt()).thenReturn(Instant.now().minusSeconds(60));

        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of(stale));
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());
        when(deviceRepository.findById(4L)).thenReturn(Optional.of(recovered));

        var result = monitor.runHealthCheck();

        assertEquals(0, result.offlineEmitted());
        assertEquals(1, result.offlineSkipped());
        verify(incidentService, never()).processEvent(any());
    }

    @Test
    void multipleStaleDevices_partialFailureDoesNotBlockOthers() {
        Device good1 = staleHeartbeatDevice(10L, Duration.ofMinutes(20));
        Device bad = mock(Device.class);
        when(bad.getId()).thenReturn(11L);
        Device good2 = staleHeartbeatDevice(12L, Duration.ofMinutes(20));

        when(deviceRepository.findRegisteredWithStaleHeartbeat(any()))
                .thenReturn(List.of(good1, bad, good2));
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());

        when(deviceRepository.findById(10L)).thenReturn(Optional.of(good1));
        // bad blows up on findById
        when(deviceRepository.findById(11L)).thenThrow(new RuntimeException("DB hiccup"));
        when(deviceRepository.findById(12L)).thenReturn(Optional.of(good2));

        when(incidentRepository.findOpenByDeviceAndEventType(any(), any())).thenReturn(Optional.empty());

        var result = monitor.runHealthCheck();

        assertEquals(2, result.offlineEmitted());
        verify(incidentService, times(2)).processEvent(any());
    }

    // ===== A3: derive-only live feed for silent devices =====

    @Test
    void staleHeartbeat_broadcastsOfflineTransitionToDashboard() {
        Device d = staleHeartbeatDevice(20L, Duration.ofMinutes(20));
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of(d));
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());
        when(deviceRepository.findById(20L)).thenReturn(Optional.of(d));
        when(incidentRepository.findOpenByDeviceAndEventType(eq(20L), eq("DEVICE_OFFLINE")))
                .thenReturn(Optional.empty());

        monitor.runHealthCheck();

        ArgumentCaptor<DeviceStatusPayload> captor = ArgumentCaptor.forClass(DeviceStatusPayload.class);
        verify(dashboardBroadcaster).deviceStatusChanged(captor.capture());
        assertEquals(20L, captor.getValue().deviceId());
        assertEquals("OFFLINE", captor.getValue().newStatus());
    }

    @Test
    void dashboardBroadcastFailure_doesNotAbortScan() {
        Device d = staleHeartbeatDevice(21L, Duration.ofMinutes(20));
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of(d));
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());
        when(deviceRepository.findById(21L)).thenReturn(Optional.of(d));
        when(incidentRepository.findOpenByDeviceAndEventType(eq(21L), eq("DEVICE_OFFLINE")))
                .thenReturn(Optional.empty());
        doThrow(new RuntimeException("redis down")).when(dashboardBroadcaster).deviceStatusChanged(any());

        var result = monitor.runHealthCheck();

        // The incident was still emitted and counted — a broadcast failure is swallowed.
        assertEquals(1, result.offlineEmitted());
        verify(incidentService).processEvent(any());
    }

    private static Device staleHeartbeatDevice(Long id, Duration sinceLastHeartbeat) {
        Device d = mock(Device.class);
        when(d.getId()).thenReturn(id);
        when(d.isDeleted()).thenReturn(false);
        when(d.getLastHeartbeatAt()).thenReturn(Instant.now().minus(sinceLastHeartbeat));
        return d;
    }

    private static Device mismatchDevice(Long id, Duration sinceMismatch) {
        Device d = mock(Device.class);
        when(d.getId()).thenReturn(id);
        when(d.isDeleted()).thenReturn(false);
        when(d.getContentMismatchSince()).thenReturn(Instant.now().minus(sinceMismatch));
        when(d.getCurrentContentVersion()).thenReturn("v-stale");
        return d;
    }
}
