package uz.orientadvertise.services.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceActionType;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RemoteActionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceActionServiceTest {

    private DeviceRepository deviceRepository;
    private RemoteActionRepository remoteActionRepository;
    private RemoteActionService remoteActionService;
    private ContentVersionService contentVersionService;
    private DeviceActionService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        remoteActionRepository = mock(RemoteActionRepository.class);
        remoteActionService = mock(RemoteActionService.class);
        contentVersionService = mock(ContentVersionService.class);
        // Default: device has resolvable content so the SYNC_CONTENT guard passes. Tests
        // that need "no playlist" override this to return null.
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn("v-current");
        service = new DeviceActionService(deviceRepository, remoteActionRepository,
                remoteActionService, contentVersionService);
    }

    @Test
    void unknownDevice_throws404() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.issueAction(404L, DeviceActionType.REBOOT, null, "alice"));
    }

    @Test
    void reboot_issuedWithEmptyPayload() {
        var device = mockDevice(1L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.countPendingByDevice(1L)).thenReturn(0L);
        when(remoteActionService.issue(any(), any(), any(), any())).thenReturn(mock(RemoteAction.class));

        service.issueAction(1L, DeviceActionType.REBOOT, null, "alice");

        ArgumentCaptor<String> typeCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        verify(remoteActionService).issue(eq(device), typeCap.capture(), payloadCap.capture(), eq("alice"));
        assertEquals("REBOOT", typeCap.getValue());
        assertEquals("{}", payloadCap.getValue());
    }

    @Test
    void volumeSet_validVolume_includesInPayload() {
        var device = mockDevice(2L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.countPendingByDevice(2L)).thenReturn(0L);
        when(remoteActionService.issue(any(), any(), any(), any())).thenReturn(mock(RemoteAction.class));

        service.issueAction(2L, DeviceActionType.VOLUME_SET, 75, "ops");

        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        verify(remoteActionService).issue(eq(device), eq("VOLUME_SET"), payloadCap.capture(), eq("ops"));
        assertTrue(payloadCap.getValue().contains("\"volume\":75"));
    }

    @Test
    void volumeSet_missingVolume_throws400_andDoesNotIssue() {
        var device = mockDevice(3L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(device));

        assertThrows(IllegalArgumentException.class,
                () -> service.issueAction(3L, DeviceActionType.VOLUME_SET, null, "ops"));
        verify(remoteActionService, never()).issue(any(), any(), any(), any());
    }

    @Test
    void volumeSet_outOfRange_throws400() {
        var device = mockDevice(4L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(device));

        assertThrows(IllegalArgumentException.class,
                () -> service.issueAction(4L, DeviceActionType.VOLUME_SET, 150, "ops"));
        assertThrows(IllegalArgumentException.class,
                () -> service.issueAction(4L, DeviceActionType.VOLUME_SET, -1, "ops"));
    }

    @Test
    void volumeSet_boundaryValues_zeroAndHundred_accepted() {
        var device = mockDevice(5L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.countPendingByDevice(5L)).thenReturn(0L);
        when(remoteActionService.issue(any(), any(), any(), any())).thenReturn(mock(RemoteAction.class));

        service.issueAction(5L, DeviceActionType.VOLUME_SET, 0, "ops");
        service.issueAction(5L, DeviceActionType.VOLUME_SET, 100, "ops");

        verify(remoteActionService, org.mockito.Mockito.times(2))
                .issue(any(), eq("VOLUME_SET"), any(), any());
    }

    @Test
    void queueCap_atTen_rejectsWith409() {
        // Edge case requirement: max 10 pending per device.
        var device = mockDevice(6L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(6L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.countPendingByDevice(6L)).thenReturn(10L);

        var ex = assertThrows(IllegalStateException.class,
                () -> service.issueAction(6L, DeviceActionType.REBOOT, null, "ops"));
        assertTrue(ex.getMessage().contains("queue cap"));
        verify(remoteActionService, never()).issue(any(), any(), any(), any());
    }

    @Test
    void queueCap_atNine_stillAccepts() {
        var device = mockDevice(7L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.countPendingByDevice(7L)).thenReturn(9L);
        when(remoteActionService.issue(any(), any(), any(), any())).thenReturn(mock(RemoteAction.class));

        service.issueAction(7L, DeviceActionType.SYNC_CONTENT, null, "ops");
        verify(remoteActionService).issue(any(), eq("SYNC_CONTENT"), any(), any());
    }

    @Test
    void duplicateType_propagatesIllegalStateFromRemoteActionService() {
        // Edge case: max 1 PENDING action of same type per device — enforced by RemoteActionService.
        var device = mockDevice(8L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(8L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.countPendingByDevice(8L)).thenReturn(1L);
        when(remoteActionService.issue(any(), eq("REBOOT"), any(), any()))
                .thenThrow(new IllegalStateException("A PENDING 'REBOOT' action already exists"));

        var ex = assertThrows(IllegalStateException.class,
                () -> service.issueAction(8L, DeviceActionType.REBOOT, null, "ops"));
        assertTrue(ex.getMessage().contains("REBOOT"));
    }

    @Test
    void allActionTypes_areAccepted() {
        var device = mockDevice(9L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(9L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.countPendingByDevice(9L)).thenReturn(0L);
        when(remoteActionService.issue(any(), any(), any(), any())).thenReturn(mock(RemoteAction.class));

        for (var type : DeviceActionType.values()) {
            Integer vol = type == DeviceActionType.VOLUME_SET ? 50 : null;
            service.issueAction(9L, type, vol, "ops");
        }
        verify(remoteActionService, org.mockito.Mockito.times(DeviceActionType.values().length))
                .issue(any(), any(), any(), any());
    }

    @Test
    void syncContent_withAssignedPlaylist_isIssued() {
        var device = mockDevice(10L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(device));
        when(remoteActionRepository.countPendingByDevice(10L)).thenReturn(0L);
        when(remoteActionService.issue(any(), any(), any(), any())).thenReturn(mock(RemoteAction.class));
        // computeExpectedVersion → non-null (default from setUp) means content is syncable.

        service.issueAction(10L, DeviceActionType.SYNC_CONTENT, null, "ops");

        verify(remoteActionService).issue(eq(device), eq("SYNC_CONTENT"), eq("{}"), eq("ops"));
    }

    @Test
    void syncContent_withNoPlaylist_rejectedWith409_andDoesNotIssue() {
        // Issue 1: a SYNC_CONTENT request against a device with no resolved playlist is a
        // no-op that would otherwise linger in pendingActions — reject it instead.
        var device = mockDevice(11L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(11L)).thenReturn(Optional.of(device));
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn(null);

        var ex = assertThrows(IllegalStateException.class,
                () -> service.issueAction(11L, DeviceActionType.SYNC_CONTENT, null, "ops"));
        assertTrue(ex.getMessage().contains("not assigned to any playlist"));
        verify(remoteActionService, never()).issue(any(), any(), any(), any());
    }

    @Test
    void noPlaylist_doesNotBlockNonSyncActions() {
        // The guard is scoped to SYNC_CONTENT — REBOOT et al. are valid with no playlist.
        var device = mockDevice(12L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(12L)).thenReturn(Optional.of(device));
        when(contentVersionService.computeExpectedVersion(any(), any())).thenReturn(null);
        when(remoteActionRepository.countPendingByDevice(12L)).thenReturn(0L);
        when(remoteActionService.issue(any(), any(), any(), any())).thenReturn(mock(RemoteAction.class));

        service.issueAction(12L, DeviceActionType.REBOOT, null, "ops");

        verify(remoteActionService).issue(eq(device), eq("REBOOT"), any(), eq("ops"));
    }

    private static Device mockDevice(Long id) {
        Device d = mock(Device.class);
        when(d.getId()).thenReturn(id);
        return d;
    }
}
