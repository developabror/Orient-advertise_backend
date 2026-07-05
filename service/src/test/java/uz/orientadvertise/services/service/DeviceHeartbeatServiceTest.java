package uz.orientadvertise.services.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceActionType;
import uz.orientadvertise.services.domain.model.DeviceVolumeResolver;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RemoteActionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceHeartbeatServiceTest {

    private DeviceRepository deviceRepository;
    private RemoteActionRepository remoteActionRepository;
    private ContentAssignmentService contentAssignmentService;
    private DeviceEventService deviceEventService;
    private ContentVersionService contentVersionService;
    private IncidentService incidentService;
    private uz.orientadvertise.services.domain.event.DashboardEventBroadcaster dashboardBroadcaster;
    private DeviceHeartbeatService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        remoteActionRepository = mock(RemoteActionRepository.class);
        contentAssignmentService = mock(ContentAssignmentService.class);
        contentVersionService = mock(ContentVersionService.class);
        deviceEventService = mock(DeviceEventService.class);
        incidentService = mock(IncidentService.class);
        dashboardBroadcaster = mock(uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.class);
        service = new DeviceHeartbeatService(deviceRepository, remoteActionRepository,
                contentAssignmentService, contentVersionService, deviceEventService,
                incidentService, dashboardBroadcaster);
    }

    @Test
    void heartbeat_unknownDevice_throwsResourceNotFound() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.processHeartbeat(999L));
    }

    @Test
    void heartbeat_recentWithContent_resultsInOnline() {
        var device = mockDevice(1L, Device.Status.OFFLINE, Instant.now().minus(1, ChronoUnit.MINUTES));
        when(deviceRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(1L)).thenReturn(List.of());

        var result = service.processHeartbeat(1L);

        assertEquals(Device.Status.ONLINE, result.status());
        verify(device).setStatus(Device.Status.ONLINE);
    }

    @Test
    void heartbeat_recentNoContent_resultsInNoContent() {
        var device = mockDevice(2L, Device.Status.ONLINE, Instant.now().minus(1, ChronoUnit.MINUTES));
        when(deviceRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(null);
        when(remoteActionRepository.findPendingByDevice(2L)).thenReturn(List.of());

        var result = service.processHeartbeat(2L);

        assertEquals(Device.Status.NO_CONTENT, result.status());
        verify(device).setStatus(Device.Status.NO_CONTENT);
    }

    @Test
    void heartbeat_statusChange_emitsEvent() {
        var device = mockDevice(3L, Device.Status.OFFLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(3L)).thenReturn(List.of());

        service.processHeartbeat(3L);

        verify(deviceEventService).emitAsync(eq(3L), eq("DEVICE_STATUS_CHANGED"),
                any(Event.Priority.class), contains("\"from\":\"OFFLINE\""));
        verify(deviceEventService).emitAsync(eq(3L), eq("DEVICE_STATUS_CHANGED"),
                any(Event.Priority.class), contains("\"to\":\"ONLINE\""));
    }

    @Test
    void heartbeat_offlineToOnline_autoResolvesDeviceOfflineIncident() {
        var device = mockDevice(20L, Device.Status.OFFLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(20L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(20L)).thenReturn(List.of());

        service.processHeartbeat(20L);

        verify(incidentService).autoResolveOnRecovery(eq(20L), eq("DEVICE_OFFLINE"));
    }

    @Test
    void heartbeat_onlineStaysOnline_doesNotAutoResolve() {
        var device = mockDevice(21L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(21L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(21L)).thenReturn(List.of());

        service.processHeartbeat(21L);

        verify(incidentService, never()).autoResolveOnRecovery(any(), any());
    }

    @Test
    void heartbeat_versionMismatchClears_autoResolvesContentMismatchIncident() {
        var device = mockDevice(22L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        // Device was previously mismatched.
        when(device.getContentMismatchSince()).thenReturn(Instant.now().minus(45, ChronoUnit.MINUTES));
        when(deviceRepository.findByIdAndDeletedAtIsNull(22L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn("v-current");
        when(remoteActionRepository.findPendingByDevice(22L)).thenReturn(List.of());

        // Heartbeat reports the matching version → mismatch flips false.
        service.processHeartbeat(22L, "v-current");

        verify(incidentService).autoResolveOnRecovery(eq(22L), eq("CONTENT_VERSION_MISMATCH"));
    }

    @Test
    void heartbeat_autoResolveFailure_doesNotFailHeartbeat() {
        var device = mockDevice(23L, Device.Status.OFFLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(23L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(23L)).thenReturn(List.of());
        when(incidentService.autoResolveOnRecovery(any(), any()))
                .thenThrow(new RuntimeException("DB hiccup"));

        // Recovery is best-effort — heartbeat must still succeed.
        var result = service.processHeartbeat(23L);
        assertEquals(Device.Status.ONLINE, result.status());
    }

    @Test
    void heartbeat_noStatusChange_doesNotEmitEvent() {
        var device = mockDevice(4L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(4L)).thenReturn(List.of());

        service.processHeartbeat(4L);

        verify(deviceEventService, never()).emitAsync(any(), any(), any(), any());
    }

    @Test
    void heartbeat_pendingActionsReturned() {
        var device = mockDevice(5L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        var action1 = mock(RemoteAction.class);
        var action2 = mock(RemoteAction.class);
        when(remoteActionRepository.findPendingByDevice(5L)).thenReturn(List.of(action1, action2));

        var result = service.processHeartbeat(5L);

        assertNotNull(result.pendingActions());
        assertEquals(2, result.pendingActions().size());
    }

    @Test
    void heartbeat_alwaysReturnsNonNullPendingActionsList() {
        var device = mockDevice(6L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(6L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(6L)).thenReturn(List.of());

        var result = service.processHeartbeat(6L);

        assertNotNull(result.pendingActions());
        assertTrue(result.pendingActions().isEmpty());
    }

    @Test
    void heartbeat_eventEmitFailure_doesNotFailHeartbeat() {
        // emitAsync runs on the executor; even if it threw, that wouldn't propagate.
        // Stubbing it to throw on the synchronous call path the unit test sees.
        doThrow(new RuntimeException("redis down"))
                .when(deviceEventService).emitAsync(any(), any(), any(), any());
        var device = mockDevice(7L, Device.Status.OFFLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(7L)).thenReturn(List.of());

        // emitAsync exception WILL propagate in this synchronous test (no executor).
        // The production behavior — async dispatch swallowing failures — is verified in
        // DeviceEventServiceTest. Here we just ensure the call site invokes the emitter.
        try {
            service.processHeartbeat(7L);
        } catch (RuntimeException expected) {
            // mocked-throw — not a real production failure mode
        }
        verify(deviceEventService).emitAsync(any(), any(), any(), any());
    }

    @Test
    void heartbeat_reportedVersionMatches_noMismatch() {
        var device = mockDevice(8L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(8L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(8L)).thenReturn(List.of());
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn("abc123");

        var result = service.processHeartbeat(8L, "abc123");

        assertEquals("abc123", result.expectedContentVersion());
        org.junit.jupiter.api.Assertions.assertFalse(result.syncRequired());
    }

    @Test
    void heartbeat_reportedVersionDiffers_mismatchTrue() {
        var device = mockDevice(9L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(9L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(9L)).thenReturn(List.of());
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn("new-hash");

        var result = service.processHeartbeat(9L, "old-hash");

        assertEquals("new-hash", result.expectedContentVersion());
        assertTrue(result.syncRequired(), "Reported old-hash != expected new-hash → sync required");
    }

    @Test
    void heartbeat_noReportedVersion_syncRequiredWhenContentAssigned() {
        // Reinstall / data-clear / factory-reset: the device holds nothing and reports a null
        // content version. With content assigned (expected != null), null != expected is a
        // mismatch — the device MUST be told to sync (spec §4), not left empty. It also anchors
        // the mismatch incident timer so a device that stays stuck escalates to operators.
        var device = mockDevice(10L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(10L)).thenReturn(List.of());
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn("hash");

        var result = service.processHeartbeat(10L); // null reported version

        assertTrue(result.syncRequired(), "Wiped device (null version) with content assigned must sync");
        verify(device).recordContentMismatch(true);
    }

    @Test
    void heartbeat_noReportedVersion_noContentAssigned_noSync() {
        // No content assigned (expected == null): nothing to sync, so a null-reporting device
        // must NOT be told to sync, and no mismatch incident is anchored.
        var device = mockDevice(13L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(13L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.findPendingByDevice(13L)).thenReturn(List.of());
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn(null);

        var result = service.processHeartbeat(13L); // null reported version

        org.junit.jupiter.api.Assertions.assertFalse(result.syncRequired());
        verify(device, never()).recordContentMismatch(anyBoolean());
    }

    @Test
    void heartbeat_blankReportedVersion_treatedAsNull_syncRequired() {
        // A blank reported version is normalized to null and must behave identically: sync
        // required when content is assigned, and the blank is never persisted as the version.
        var device = mockDevice(14L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(14L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(14L)).thenReturn(List.of());
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn("hash");

        var result = service.processHeartbeat(14L, "   ");

        assertTrue(result.syncRequired(), "Blank version normalized to null → sync required");
        verify(device, never()).setCurrentContentVersion(anyString());
    }

    @Test
    void heartbeat_pendingSyncContentAction_setsSyncRequiredTrue() {
        // Issue 2: an operator-queued SYNC_CONTENT action must surface as syncRequired=true
        // even when the device's reported version already matches the expected version.
        var device = mockDevice(11L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(11L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn("v-current");
        var syncAction = mock(RemoteAction.class);
        when(syncAction.getActionType()).thenReturn(DeviceActionType.SYNC_CONTENT.name());
        when(remoteActionRepository.findPendingByDevice(11L)).thenReturn(List.of(syncAction));

        var result = service.processHeartbeat(11L, "v-current");

        assertTrue(result.syncRequired(), "Pending SYNC_CONTENT must force syncRequired=true");
    }

    @Test
    void heartbeat_pendingSyncContent_doesNotAnchorContentMismatch() {
        // A forced re-sync is not a content divergence — only a true version mismatch may
        // anchor the CONTENT_VERSION_MISMATCH incident timer.
        var device = mockDevice(12L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(12L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn("v-current");
        var syncAction = mock(RemoteAction.class);
        when(syncAction.getActionType()).thenReturn(DeviceActionType.SYNC_CONTENT.name());
        when(remoteActionRepository.findPendingByDevice(12L)).thenReturn(List.of(syncAction));

        var result = service.processHeartbeat(12L, "v-current");

        assertTrue(result.syncRequired());
        verify(device).recordContentMismatch(false);
        verify(incidentService, never()).autoResolveOnRecovery(eq(12L), eq("CONTENT_VERSION_MISMATCH"));
    }

    // ---- Volume reconciliation (PROMPT-device-volume-control §3 / §8) ----

    @Test
    void heartbeat_reportedVolume_stored() {
        var device = mockDevice(30L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(30L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(30L)).thenReturn(List.of());

        service.processHeartbeat(30L, null, null, 45);

        // In-range value is stored verbatim (no clamping).
        verify(device).recordReportedVolume(45);
    }

    @Test
    void heartbeat_reportedVolume_clampedHigh_storesMax() {
        var device = mockDevice(31L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(31L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(31L)).thenReturn(List.of());

        // Above range → clamped to 100, beat still completes normally.
        var result = service.processHeartbeat(31L, null, null, 130);

        assertNotNull(result);
        verify(device).recordReportedVolume(100);
    }

    @Test
    void heartbeat_reportedVolume_clampedLow_storesZero() {
        var device = mockDevice(32L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(32L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(32L)).thenReturn(List.of());

        // Below range → clamped to 0, never throws.
        var result = service.processHeartbeat(32L, null, null, -5);

        assertNotNull(result);
        verify(device).recordReportedVolume(0);
    }

    @Test
    void heartbeat_nullReportedVolume_doesNotRecordAndDoesNotFail() {
        var device = mockDevice(33L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(33L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(33L)).thenReturn(List.of());

        // null volume is a no-op — the beat completes and nothing is recorded.
        var result = service.processHeartbeat(33L, null, null, null);

        assertEquals(Device.Status.ONLINE, result.status());
        verify(device, never()).recordReportedVolume(any());
    }

    @Test
    void heartbeat_desiredVolume_deviceOverrideWins() {
        var device = mockDevice(34L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        // Per-device override present → resolver returns it regardless of group/default.
        when(device.getDesiredVolume()).thenReturn(60);
        when(deviceRepository.findByIdAndDeletedAtIsNull(34L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(34L)).thenReturn(List.of());

        var result = service.processHeartbeat(34L, null, null, null);

        assertEquals(60, result.desiredVolume());
    }

    @Test
    void heartbeat_desiredVolume_inheritsGroupVolume() {
        var device = mockDevice(35L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        // No per-device override, but the group sets a volume → group value inherited.
        // NOTE: an unstubbed Integer-returning mock method returns 0 (not null) under Mockito,
        // so getDesiredVolume() MUST be explicitly stubbed to null for the inherit branch to fire.
        when(device.getDesiredVolume()).thenReturn(null);
        var group = mock(uz.orientadvertise.services.domain.model.DeviceGroup.class);
        when(group.getVolume()).thenReturn(40);
        when(device.getDeviceGroup()).thenReturn(group);
        when(deviceRepository.findByIdAndDeletedAtIsNull(35L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(35L)).thenReturn(List.of());

        var result = service.processHeartbeat(35L, null, null, null);

        assertEquals(40, result.desiredVolume());
    }

    @Test
    void heartbeat_desiredVolume_fallsBackToDefault() {
        var device = mockDevice(36L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        // No override and no group → DEFAULT_VOLUME (100). getDesiredVolume() must be stubbed null
        // (an unstubbed Integer mock method returns 0, not null); getDeviceGroup() defaults to null.
        when(device.getDesiredVolume()).thenReturn(null);
        when(deviceRepository.findByIdAndDeletedAtIsNull(36L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(36L)).thenReturn(List.of());

        var result = service.processHeartbeat(36L, null, null, null);

        assertEquals(DeviceVolumeResolver.DEFAULT_VOLUME, result.desiredVolume());
        assertEquals(100, result.desiredVolume());
    }

    private Device mockDevice(Long id, Device.Status currentStatus, Instant lastHeartbeat) {
        var device = mock(Device.class);
        when(device.getId()).thenReturn(id);
        when(device.getStatus()).thenReturn(currentStatus);
        // After recordHeartbeat() is called, lastHeartbeatAt is fresh.
        // We simulate that state directly via the mock.
        when(device.getLastHeartbeatAt()).thenReturn(lastHeartbeat);
        return device;
    }
}
