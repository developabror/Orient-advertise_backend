package uz.orientadvertise.services.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeviceHeartbeatServiceTest {

    private DeviceRepository deviceRepository;
    private RemoteActionRepository remoteActionRepository;
    private ContentAssignmentService contentAssignmentService;
    private DeviceEventService deviceEventService;
    private ContentVersionService contentVersionService;
    private IncidentService incidentService;
    private uz.orientadvertise.services.domain.event.DashboardEventBroadcaster dashboardBroadcaster;
    private RemoteSessionService remoteSessionService;
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
        remoteSessionService = mock(RemoteSessionService.class);
        // Default: no session wanted. Individual tests override.
        when(remoteSessionService.desiredFor(any())).thenReturn(Optional.empty());
        service = new DeviceHeartbeatService(deviceRepository, remoteActionRepository,
                contentAssignmentService, contentVersionService, deviceEventService,
                incidentService, dashboardBroadcaster, remoteSessionService);
    }

    @Test
    void heartbeat_recovery_neverTouchesIncidentsInsideTheBeat() {
        // Resolving inside the beat's transaction holds a second pooled connection per recovery
        // beat (after a regional outage every device recovers at once → pool deadlock) or, if it
        // joins, lets a failed resolve roll the beat back. processHeartbeat only REPORTS the types;
        // the controller resolves them after commit via resolveRecoveredIncidents. A real entity:
        // registered, never beaten.
        var device = new Device(mock(uz.orientadvertise.services.domain.model.Region.class), null, "SN-R", "R");
        device.register("dtk");
        when(deviceRepository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(99L)).thenReturn(List.of());

        var result = service.processHeartbeat(99L);

        assertEquals(List.of("DEVICE_OFFLINE"), result.resolveIncidentTypes());
        verifyNoInteractions(incidentService);
    }

    @Test
    void resolveRecoveredIncidents_resolvesEachReportedType() {
        var result = resultResolving(60L, "DEVICE_OFFLINE", "CONTENT_VERSION_MISMATCH");

        service.resolveRecoveredIncidents(60L, result);

        verify(incidentService).autoResolveOnRecovery(60L, "DEVICE_OFFLINE");
        verify(incidentService).autoResolveOnRecovery(60L, "CONTENT_VERSION_MISMATCH");
    }

    @Test
    void resolveRecoveredIncidents_nothingReported_touchesNothing() {
        service.resolveRecoveredIncidents(61L, new DeviceHeartbeatService.HeartbeatResult(61L, Device.Status.ONLINE, List.of()));

        verifyNoInteractions(incidentService);
    }

    @Test
    void resolveRecoveredIncidents_aFailingResolve_isSwallowed_andTheNextTypeStillRuns() {
        // Best-effort: the beat has already committed; a DB hiccup here is logged and left to the
        // health monitor's recovery sweep.
        when(incidentService.autoResolveOnRecovery(62L, "DEVICE_OFFLINE"))
                .thenThrow(new RuntimeException("DB hiccup"));

        service.resolveRecoveredIncidents(62L, resultResolving(62L, "DEVICE_OFFLINE", "CONTENT_VERSION_MISMATCH"));

        verify(incidentService).autoResolveOnRecovery(62L, "CONTENT_VERSION_MISMATCH");
    }

    @Test
    void heartbeat_unknownDevice_throwsResourceNotFound() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.processHeartbeat(999L));
    }

    @Test
    void heartbeat_recentWithContent_resultsInOnline() {
        // Stored NO_CONTENT, content now assigned. (Production never stores OFFLINE — see mockDevice.)
        var device = mockDevice(1L, Device.Status.NO_CONTENT, Instant.now().minus(1, ChronoUnit.MINUTES));
        when(deviceRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(1L)).thenReturn(List.of());

        var result = service.processHeartbeat(1L);

        assertEquals(Device.Status.ONLINE, result.status());
        verify(device).setStatus(Device.Status.ONLINE);
    }

    @Test
    void heartbeat_closesAnOpenReregistrationWindow() {
        // AUTH-02: a beat proves the box holds its token; an open window would only let someone
        // else take the device.
        var device = mockDevice(3L, Device.Status.ONLINE, Instant.now().minus(1, ChronoUnit.MINUTES));
        when(deviceRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.findPendingByDevice(3L)).thenReturn(List.of());

        service.processHeartbeat(3L);

        verify(device).closeReregistrationWindow();
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
        // Stored ONLINE, but silent for 20 minutes: the device WAS offline, so the beat is an
        // OFFLINE→ONLINE transition even though the status column never said OFFLINE.
        var device = mockDevice(3L, Device.Status.ONLINE, Instant.now().minus(20, ChronoUnit.MINUTES));
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
        var device = mockDevice(20L, Device.Status.ONLINE, Instant.now().minus(20, ChronoUnit.MINUTES));
        when(deviceRepository.findByIdAndDeletedAtIsNull(20L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(20L)).thenReturn(List.of());

        var result = service.processHeartbeat(20L);

        assertEquals(List.of("DEVICE_OFFLINE"), result.resolveIncidentTypes());
        verifyNoInteractions(incidentService);
    }

    @Test
    void heartbeat_staleBeatWithUnchangedStoredStatus_stillResolvesDeviceOfflineIncident() {
        // LOGIC-01 exactly: the stored status (NO_CONTENT) equals the status this beat derives, so
        // a stored-status comparison sees "no change" — yet the device was silent for 30 minutes
        // and the health monitor has opened a DEVICE_OFFLINE incident for it.
        var device = mockDevice(24L, Device.Status.NO_CONTENT, Instant.now().minus(30, ChronoUnit.MINUTES));
        when(deviceRepository.findByIdAndDeletedAtIsNull(24L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(null);
        when(remoteActionRepository.findPendingByDevice(24L)).thenReturn(List.of());

        var result = service.processHeartbeat(24L);

        assertEquals(Device.Status.NO_CONTENT, result.status());
        assertEquals(List.of("DEVICE_OFFLINE"), result.resolveIncidentTypes());
        verify(deviceEventService).emitAsync(eq(24L), eq("DEVICE_STATUS_CHANGED"),
                any(Event.Priority.class), eq("{\"from\":\"OFFLINE\",\"to\":\"NO_CONTENT\"}"));
    }

    @Test
    void heartbeat_withinThreshold_doesNotResolveOrEmit() {
        // Negative: 14 minutes of silence is inside DeviceHealthMonitor.HEARTBEAT_THRESHOLD, so no
        // incident can have been opened and the device never counted as offline.
        var device = mockDevice(25L, Device.Status.ONLINE, Instant.now().minus(14, ChronoUnit.MINUTES));
        when(deviceRepository.findByIdAndDeletedAtIsNull(25L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(25L)).thenReturn(List.of());

        var result = service.processHeartbeat(25L);

        assertTrue(result.resolveIncidentTypes().isEmpty());
        verify(deviceEventService, never()).emitAsync(any(), any(), any(), any());
        verify(dashboardBroadcaster, never()).deviceStatusChanged(any());
    }

    @Test
    void heartbeat_betweenMonitorAndDisplayThresholds_resolvesButAnnouncesNoTransition() {
        // 15.5 min: past DeviceHealthMonitor's 15-min escalation threshold (an incident may be
        // open → resolve it), but inside DeviceStatusEvaluator's 15 min + 60 s grace — no surface
        // ever showed this device OFFLINE, so no OFFLINE→ONLINE event or broadcast.
        var device = mockDevice(27L, Device.Status.ONLINE, Instant.now().minusSeconds(15 * 60 + 30));
        when(deviceRepository.findByIdAndDeletedAtIsNull(27L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(27L)).thenReturn(List.of());

        var result = service.processHeartbeat(27L);

        assertEquals(List.of("DEVICE_OFFLINE"), result.resolveIncidentTypes());
        verify(deviceEventService, never()).emitAsync(any(), any(), any(), any());
        verify(dashboardBroadcaster, never()).deviceStatusChanged(any());
    }

    @Test
    void heartbeat_pastTheDisplayThreshold_announcesOfflineToOnline() {
        // 16.5 min: past 15 min + 60 s grace, so the device WAS showing OFFLINE everywhere.
        var device = mockDevice(28L, Device.Status.ONLINE, Instant.now().minusSeconds(16 * 60 + 30));
        when(deviceRepository.findByIdAndDeletedAtIsNull(28L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(28L)).thenReturn(List.of());

        var result = service.processHeartbeat(28L);

        assertEquals(List.of("DEVICE_OFFLINE"), result.resolveIncidentTypes());
        verify(deviceEventService).emitAsync(eq(28L), eq("DEVICE_STATUS_CHANGED"),
                eq(Event.Priority.MEDIUM), eq("{\"from\":\"OFFLINE\",\"to\":\"ONLINE\"}"));
        verify(dashboardBroadcaster).deviceStatusChanged(any());
    }

    @Test
    void heartbeat_firstBeatEver_isAnOfflineToOnlineTransition() {
        // A registered device that has never beaten (lastHeartbeatAt null) counts as coming online.
        var device = mockDevice(26L, Device.Status.UNREGISTERED, null);
        when(deviceRepository.findByIdAndDeletedAtIsNull(26L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(26L)).thenReturn(List.of());

        var result = service.processHeartbeat(26L);

        assertEquals(Device.Status.ONLINE, result.status());
        verify(deviceEventService).emitAsync(eq(26L), eq("DEVICE_STATUS_CHANGED"),
                eq(Event.Priority.MEDIUM), eq("{\"from\":\"OFFLINE\",\"to\":\"ONLINE\"}"));
        assertEquals(List.of("DEVICE_OFFLINE"), result.resolveIncidentTypes());
    }

    @Test
    void heartbeat_onlineStaysOnline_doesNotAutoResolve() {
        var device = mockDevice(21L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(21L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(21L)).thenReturn(List.of());

        var result = service.processHeartbeat(21L);

        assertTrue(result.resolveIncidentTypes().isEmpty());
        verifyNoInteractions(incidentService);
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
        var result = service.processHeartbeat(22L, "v-current");

        assertEquals(List.of("CONTENT_VERSION_MISMATCH"), result.resolveIncidentTypes());
        verifyNoInteractions(incidentService);
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
        var device = mockDevice(7L, Device.Status.ONLINE, Instant.now().minus(20, ChronoUnit.MINUTES));
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
    void heartbeat_assignmentEndedWhileMismatched_clearsTheAnchorAndClosesTheIncident() {
        // VG-15. The campaign ended (expected == null) while this device was still diverging. There
        // is nothing left to diverge FROM, so the anchor must go and the incident with it. The whole
        // mismatch block used to be gated on expected != null, so neither happened: the incident
        // stayed open, and the health monitor — which re-checks the same anchor — would escalate it
        // again after an operator resolved it by hand.
        var device = mockDevice(30L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(device.getContentMismatchSince()).thenReturn(Instant.now().minus(2, ChronoUnit.HOURS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(30L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.findPendingByDevice(30L)).thenReturn(List.of());
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn(null);

        var result = service.processHeartbeat(30L, "v-stale");

        verify(device).recordContentMismatch(false);
        assertTrue(result.resolveIncidentTypes().contains(DeviceHealthMonitor.EVENT_CONTENT_MISMATCH),
                "an incident about content nobody expects any more has to close");
        org.junit.jupiter.api.Assertions.assertFalse(result.syncRequired(),
                "there is nothing to sync when no content is assigned");
    }

    @Test
    void heartbeat_noContentAssignedAndNeverMismatched_writesNothing() {
        // The common case for an unassigned device: no anchor to clear, so the row is not touched
        // and no incident resolution is queued on every single beat.
        var device = mockDevice(31L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(device.getContentMismatchSince()).thenReturn(null);
        when(deviceRepository.findByIdAndDeletedAtIsNull(31L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.findPendingByDevice(31L)).thenReturn(List.of());
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn(null);

        var result = service.processHeartbeat(31L, null);

        verify(device, never()).recordContentMismatch(anyBoolean());
        assertTrue(result.resolveIncidentTypes().isEmpty());
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
        assertTrue(result.resolveIncidentTypes().isEmpty());
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

    // ----- remote view/control: capability up, desired state down (§4.2 of the contract) -----
    //
    // The governing rule, copied verbatim from the `volume` contract: a malformed or absent
    // `remote` block MUST NEVER fail the beat. The heartbeat is a liveness signal first — a
    // buggy capability reporter must not be able to take a fleet offline.

    @Test
    void heartbeat_remoteBlockAbsent_beatSucceedsAndCapabilityIsUntouched() {
        var device = mockDevice(40L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(40L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(40L)).thenReturn(List.of());

        var result = service.processHeartbeat(40L, null, null, null, null);

        assertEquals(Device.Status.ONLINE, result.status());
        verify(device, never()).recordRemoteCapability(any(), any(), any(), any(), any(), any());
    }

    @Test
    void heartbeat_oldClientFourArgOverload_stillWorksAndReportsNoCapability() {
        // The exact call an un-upgraded caller makes. Byte-for-byte the old behaviour.
        var device = mockDevice(41L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(41L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(41L)).thenReturn(List.of());

        var result = service.processHeartbeat(41L, "v1", "10.0.0.1", 45);

        assertEquals(Device.Status.ONLINE, result.status());
        assertNull(result.desiredRemoteSession());
        verify(device, never()).recordRemoteCapability(any(), any(), any(), any(), any(), any());
    }

    @Test
    void heartbeat_wellFormedRemoteBlock_isRecordedNormalized() {
        var device = mockDevice(42L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(42L)).thenReturn(List.of());

        service.processHeartbeat(42L, null, null, null,
                new DeviceHeartbeatService.RemoteCapabilityReport(true, "root", "scrcpy_ws", 1280, 720));

        verify(device).recordRemoteCapability(eq(true), eq("ROOT"), eq("SCRCPY_WS"),
                eq(1280), eq(720), any());
    }

    @Test
    void heartbeat_malformedRemoteBlock_beatSucceedsAndBadFieldsAreDropped() {
        // Unknown enum tokens and an absurd resolution: dropped to null, WARN logged, beat lives.
        var device = mockDevice(43L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(43L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(43L)).thenReturn(List.of());

        var result = service.processHeartbeat(43L, null, null, null,
                new DeviceHeartbeatService.RemoteCapabilityReport(true, "MAGIC", "TELEPATHY", -1, 99_999));

        assertEquals(Device.Status.ONLINE, result.status(), "a malformed remote block must never fail the beat");
        verify(device).recordRemoteCapability(eq(true), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    void heartbeat_capabilityRecordingThrows_beatStillSucceeds() {
        var device = mockDevice(44L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(44L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(44L)).thenReturn(List.of());
        doThrow(new RuntimeException("column vanished"))
                .when(device).recordRemoteCapability(any(), any(), any(), any(), any(), any());

        var result = service.processHeartbeat(44L, null, null, null,
                new DeviceHeartbeatService.RemoteCapabilityReport(true, "ROOT", "SCRCPY_WS", 1280, 720));

        assertEquals(Device.Status.ONLINE, result.status());
    }

    @Test
    void heartbeat_desiredRemoteSession_isReturnedWhenOneIsPending() {
        var device = mockDevice(45L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(45L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(45L)).thenReturn(List.of());
        var desired = new RemoteSessionService.DesiredRemoteSession("rs_abc123",
                "wss://relay.test/agent", "tkt", Instant.now().plus(30, ChronoUnit.MINUTES),
                false, 1280, 15, 2_000_000);
        when(remoteSessionService.desiredFor(45L)).thenReturn(Optional.of(desired));

        var result = service.processHeartbeat(45L, null, null, null, null);

        assertNotNull(result.desiredRemoteSession());
        assertEquals("rs_abc123", result.desiredRemoteSession().sessionId());
        assertEquals("wss://relay.test/agent", result.desiredRemoteSession().relayUrl());
    }

    @Test
    void heartbeat_desiredRemoteSession_isNullWhenThereIsNone() {
        var device = mockDevice(46L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(46L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(46L)).thenReturn(List.of());
        when(remoteSessionService.desiredFor(46L)).thenReturn(Optional.empty());

        assertNull(service.processHeartbeat(46L, null, null, null, null).desiredRemoteSession(),
                "null means: no session wanted; stop any running one");
    }

    @Test
    void heartbeat_desiredSessionLookupThrows_beatStillSucceedsWithNull() {
        var device = mockDevice(47L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(47L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(47L)).thenReturn(List.of());
        when(remoteSessionService.desiredFor(47L)).thenThrow(new RuntimeException("db down"));

        var result = service.processHeartbeat(47L, null, null, null, null);

        assertEquals(Device.Status.ONLINE, result.status());
        assertNull(result.desiredRemoteSession());
    }

    @Test
    void heartbeat_capabilityAndVolumeAndVersion_allApplyInOneBeat() {
        var device = mockDevice(48L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(48L)).thenReturn(Optional.of(device));
        when(contentAssignmentService.resolveForDevice(any(), any())).thenReturn(mock(ContentAssignment.class));
        when(remoteActionRepository.findPendingByDevice(48L)).thenReturn(List.of());

        service.processHeartbeat(48L, "v9", "10.0.0.5", 45,
                new DeviceHeartbeatService.RemoteCapabilityReport(true, "ROOT", "SCRCPY_WS", 1280, 720));

        verify(device).recordReportedVolume(45);
        verify(device).setCurrentContentVersion("v9");
        verify(device).setLastKnownIp("10.0.0.5");
        verify(device).recordRemoteCapability(eq(true), eq("ROOT"), eq("SCRCPY_WS"), eq(1280), eq(720), any());
    }

    @Test
    void heartbeat_oversizedSourceIp_isNotStored() {
        // RemoteIpValve copies X-Forwarded-For from a trusted proxy unvalidated; last_known_ip is
        // VARCHAR(45). A garbage value must never fail the liveness signal.
        var device = mockDevice(49L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(49L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.findPendingByDevice(49L)).thenReturn(List.of());

        service.processHeartbeat(49L, null, "x".repeat(46), null);

        verify(device, never()).setLastKnownIp(any());
    }

    @Test
    void heartbeat_sourceIpAtColumnWidth_isStored() {
        var device = mockDevice(50L, Device.Status.ONLINE, Instant.now().minus(30, ChronoUnit.SECONDS));
        when(deviceRepository.findByIdAndDeletedAtIsNull(50L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.findPendingByDevice(50L)).thenReturn(List.of());
        var ipv6WithZone = "fe80:0000:0000:0000:0000:0000:0000:0001%eth01"; // exactly 45 chars

        service.processHeartbeat(50L, null, ipv6WithZone, null);

        verify(device).setLastKnownIp(ipv6WithZone);
    }

    /**
     * {@code storedStatus} is the status column. Production never stores OFFLINE (the health
     * monitor is derive-only), so an offline device is modelled by a STALE
     * {@code previousHeartbeat}, not by the stored status. {@code getLastHeartbeatAt()} behaves
     * like the entity: it returns {@code previousHeartbeat} until {@code recordHeartbeat()} runs,
     * and "now" afterwards — so a test only passes if the service reads the previous beat first.
     */
    private static DeviceHeartbeatService.HeartbeatResult resultResolving(Long deviceId, String... types) {
        return new DeviceHeartbeatService.HeartbeatResult(deviceId, Device.Status.ONLINE, List.of(), null, false,
                DeviceVolumeResolver.DEFAULT_VOLUME, null, null, List.of(types));
    }

    private Device mockDevice(Long id, Device.Status storedStatus, Instant previousHeartbeat) {
        var device = mock(Device.class);
        var lastHeartbeat = new AtomicReference<>(previousHeartbeat);
        when(device.getId()).thenReturn(id);
        when(device.getStatus()).thenReturn(storedStatus);
        when(device.getLastHeartbeatAt()).thenAnswer(inv -> lastHeartbeat.get());
        doAnswer(inv -> {
            lastHeartbeat.set(Instant.now());
            return null;
        }).when(device).recordHeartbeat();
        return device;
    }
}
