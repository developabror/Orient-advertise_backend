package uz.orientadvertise.services.service;

import java.lang.reflect.Field;
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
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    private ContentVersionService contentVersionService;
    private DeviceHealthMonitor monitor;

    @BeforeEach
    void setUp() throws Exception {
        deviceRepository = mock(DeviceRepository.class);
        incidentRepository = mock(IncidentRepository.class);
        incidentService = mock(IncidentService.class);
        dashboardService = mock(DashboardService.class);
        dashboardBroadcaster = mock(DashboardEventBroadcaster.class);
        contentVersionService = mock(ContentVersionService.class);
        // Default: the device still has content assigned, so a mismatch is a real divergence.
        // VG-15 is the opposite case, and the tests for it stub this to null.
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn("v-expected");
        monitor = new DeviceHealthMonitor(deviceRepository, incidentRepository,
                incidentService, dashboardService, dashboardBroadcaster, contentVersionService, null);
        // Self-reference for the @Transactional(REQUIRES_NEW) escalate indirection. The unit
        // test points it at the same instance — that the proxy IS used is what
        // DeviceMonitorTransactionIntegrationTest proves against the real transaction manager.
        Field selfField = DeviceHealthMonitor.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(monitor, monitor);
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
        when(incidentRepository.existsOpenByDeviceAndEventType(eq(1L), eq("DEVICE_OFFLINE")))
.thenReturn(false);

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
        when(incidentRepository.existsOpenByDeviceAndEventType(eq(2L), eq("CONTENT_VERSION_MISMATCH")))
.thenReturn(false);

        var result = monitor.runHealthCheck();

        assertEquals(1, result.mismatchEmitted());
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(incidentService).processEvent(captor.capture());
        assertEquals("CONTENT_VERSION_MISMATCH", captor.getValue().getEventType());
        assertEquals(Event.Priority.MEDIUM, captor.getValue().getPriority());
    }

    @Test
    void staleMismatchButNoContentExpected_isNotEscalated() {
        // VG-15. The device stopped beating while diverging and its assignment has since ended, so
        // the anchor is still set but the server expects nothing of it. Escalating here raises an
        // incident about content that is not assigned — and, since the anchor never clears, raises
        // it again after an operator resolves it by hand.
        Device d = mismatchDevice(20L, Duration.ofMinutes(45));
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of());
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of(d));
        when(deviceRepository.findById(20L)).thenReturn(Optional.of(d));
        when(incidentRepository.existsOpenByDeviceAndEventType(eq(20L), eq("CONTENT_VERSION_MISMATCH")))
                .thenReturn(false);
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn(null);

        var result = monitor.runHealthCheck();

        assertEquals(0, result.mismatchEmitted());
        assertEquals(1, result.mismatchSkipped());
        verify(incidentService, never()).processEvent(any());
    }

    @Test
    void openMismatchIncident_isClosedOnceNoContentIsExpected() {
        // The other half of VG-15: a device that will never beat again cannot clear its own anchor,
        // so the sweep closes the incident instead of leaving it open forever.
        Device d = mismatchDevice(21L, Duration.ofMinutes(45));
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of());
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());
        when(incidentRepository.findDeviceIdsWithOpenIncident("CONTENT_VERSION_MISMATCH"))
                .thenReturn(List.of(21L));
        when(deviceRepository.findByIdAndDeletedAtIsNull(21L)).thenReturn(Optional.of(d));
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn(null);
        when(incidentService.autoResolveOnRecovery(21L, "CONTENT_VERSION_MISMATCH")).thenReturn(true);

        monitor.runHealthCheck();

        verify(incidentService).autoResolveOnRecovery(21L, "CONTENT_VERSION_MISMATCH");
    }

    @Test
    void openMismatchIncident_staysOpenWhileContentIsStillExpected() {
        // Negative: a device that really is diverging from assigned content keeps its incident.
        Device d = mismatchDevice(22L, Duration.ofMinutes(45));
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of());
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());
        when(incidentRepository.findDeviceIdsWithOpenIncident("CONTENT_VERSION_MISMATCH"))
                .thenReturn(List.of(22L));
        when(deviceRepository.findByIdAndDeletedAtIsNull(22L)).thenReturn(Optional.of(d));

        monitor.runHealthCheck();

        verify(incidentService, never()).autoResolveOnRecovery(eq(22L), any());
    }

    @Test
    void existingOpenIncident_skipsEmission_avoidsDuplicates() {
        // Re-check incident status before opening — recovery edge case.
        Device d = staleHeartbeatDevice(3L, Duration.ofMinutes(60));
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of(d));
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());
        when(deviceRepository.findById(3L)).thenReturn(Optional.of(d));
        when(incidentRepository.existsOpenByDeviceAndEventType(eq(3L), eq("DEVICE_OFFLINE")))
.thenReturn(true);

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

        when(incidentRepository.existsOpenByDeviceAndEventType(any(), any()))
.thenReturn(false);

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
        when(incidentRepository.existsOpenByDeviceAndEventType(eq(20L), eq("DEVICE_OFFLINE")))
.thenReturn(false);

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
        when(incidentRepository.existsOpenByDeviceAndEventType(eq(21L), eq("DEVICE_OFFLINE")))
.thenReturn(false);
        doThrow(new RuntimeException("redis down")).when(dashboardBroadcaster).deviceStatusChanged(any());

        var result = monitor.runHealthCheck();

        // The incident was still emitted and counted — a broadcast failure is swallowed.
        assertEquals(1, result.offlineEmitted());
        verify(incidentService).processEvent(any());
    }

    @Test
    void offlineBroadcast_takesProjectFromTheEscalation_neverFromTheDetachedDevice() {
        // LOGIC-03: the candidate list holds DETACHED devices — navigating their LAZY region
        // throws LazyInitializationException. The stub makes that explicit; the project must come
        // from the device escalate() re-read inside its own transaction.
        Device detached = staleHeartbeatDevice(22L, Duration.ofMinutes(20));
        when(detached.getRegion()).thenThrow(new IllegalStateException("LazyInitializationException"));
        Device managed = staleHeartbeatDevice(22L, Duration.ofMinutes(20));
        var project = mock(Project.class);
        when(project.getId()).thenReturn(77L);
        var region = mock(Region.class);
        when(region.getProject()).thenReturn(project);
        when(managed.getRegion()).thenReturn(region);
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of(detached));
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());
        when(deviceRepository.findById(22L)).thenReturn(Optional.of(managed));
        when(incidentRepository.existsOpenByDeviceAndEventType(eq(22L), eq("DEVICE_OFFLINE")))
.thenReturn(false);

        monitor.runHealthCheck();

        ArgumentCaptor<DeviceStatusPayload> captor = ArgumentCaptor.forClass(DeviceStatusPayload.class);
        verify(dashboardBroadcaster).deviceStatusChanged(captor.capture());
        assertEquals(77L, captor.getValue().projectId());
    }

    @Test
    void escalate_returnsProjectIdOnlyWhenItEscalates() {
        Device managed = staleHeartbeatDevice(23L, Duration.ofMinutes(20));
        var project = mock(Project.class);
        when(project.getId()).thenReturn(78L);
        var region = mock(Region.class);
        when(region.getProject()).thenReturn(project);
        when(managed.getRegion()).thenReturn(region);
        when(deviceRepository.findById(23L)).thenReturn(Optional.of(managed));
        when(incidentRepository.existsOpenByDeviceAndEventType(eq(23L), eq("DEVICE_OFFLINE")))
                .thenReturn(false, true);

        var first = monitor.escalate(23L, "DEVICE_OFFLINE", Event.Priority.CRITICAL, "{}", Instant.now());
        var second = monitor.escalate(23L, "DEVICE_OFFLINE", Event.Priority.CRITICAL, "{}", Instant.now());

        assertEquals(new DeviceHealthMonitor.EscalationOutcome(true, 78L), first);
        assertEquals(new DeviceHealthMonitor.EscalationOutcome(false, null), second);
    }

    // ===== LOGIC-01: recovery sweep for incidents the heartbeat did not close =====

    @Test
    void sweep_resolvesOpenOfflineIncidentOfRecoveredDevice_andInvalidatesDashboard() {
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of());
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());
        when(incidentRepository.findDeviceIdsWithOpenIncidentAndHeartbeatAfter(eq("DEVICE_OFFLINE"), any()))
                .thenReturn(List.of(30L, 31L));
        when(incidentService.autoResolveOnRecovery(30L, "DEVICE_OFFLINE")).thenReturn(true);
        when(incidentService.autoResolveOnRecovery(31L, "DEVICE_OFFLINE")).thenReturn(true);

        monitor.runHealthCheck();

        verify(incidentService).autoResolveOnRecovery(30L, "DEVICE_OFFLINE");
        verify(incidentService).autoResolveOnRecovery(31L, "DEVICE_OFFLINE");
        verify(dashboardService).invalidate();
    }

    @Test
    void sweep_usesTheSameThresholdInstantAsTheEscalation() {
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of());
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());

        monitor.runHealthCheck();

        ArgumentCaptor<Instant> escalation = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> sweep = ArgumentCaptor.forClass(Instant.class);
        verify(deviceRepository).findRegisteredWithStaleHeartbeat(escalation.capture());
        verify(incidentRepository).findDeviceIdsWithOpenIncidentAndHeartbeatAfter(eq("DEVICE_OFFLINE"), sweep.capture());
        assertEquals(escalation.getValue(), sweep.getValue());
        Instant expected = Instant.now().minus(DeviceHealthMonitor.HEARTBEAT_THRESHOLD);
        assertTrue(Duration.between(sweep.getValue(), expected).abs().compareTo(Duration.ofSeconds(5)) < 0,
                "threshold must be now - HEARTBEAT_THRESHOLD, was " + sweep.getValue());
    }

    @Test
    void sweep_nothingResolved_doesNotInvalidateDashboard() {
        // Negative: the incident was manually resolved meanwhile → autoResolve returns false.
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of());
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());
        when(incidentRepository.findDeviceIdsWithOpenIncidentAndHeartbeatAfter(eq("DEVICE_OFFLINE"), any()))
                .thenReturn(List.of(32L));
        when(incidentService.autoResolveOnRecovery(32L, "DEVICE_OFFLINE")).thenReturn(false);

        monitor.runHealthCheck();

        verify(dashboardService, never()).invalidate();
    }

    @Test
    void sweep_failures_neverFailTheHealthCheck() {
        Device d = staleHeartbeatDevice(33L, Duration.ofMinutes(20));
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of(d));
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());
        when(deviceRepository.findById(33L)).thenReturn(Optional.of(d));
        when(incidentRepository.existsOpenByDeviceAndEventType(eq(33L), eq("DEVICE_OFFLINE")))
.thenReturn(false);
        when(incidentRepository.findDeviceIdsWithOpenIncidentAndHeartbeatAfter(any(), any()))
                .thenThrow(new RuntimeException("db hiccup"));

        var result = monitor.runHealthCheck();

        assertEquals(1, result.offlineEmitted());
        verify(dashboardService).invalidate();
    }

    @Test
    void sweep_oneFailingResolve_doesNotBlockTheOthers() {
        when(deviceRepository.findRegisteredWithStaleHeartbeat(any())).thenReturn(List.of());
        when(deviceRepository.findRegisteredWithStaleContentMismatch(any())).thenReturn(List.of());
        when(incidentRepository.findDeviceIdsWithOpenIncidentAndHeartbeatAfter(eq("DEVICE_OFFLINE"), any()))
                .thenReturn(List.of(34L, 35L));
        when(incidentService.autoResolveOnRecovery(34L, "DEVICE_OFFLINE")).thenThrow(new RuntimeException("boom"));
        when(incidentService.autoResolveOnRecovery(35L, "DEVICE_OFFLINE")).thenReturn(true);

        monitor.runHealthCheck();

        verify(incidentService).autoResolveOnRecovery(35L, "DEVICE_OFFLINE");
        verify(dashboardService).invalidate();
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
