package uz.orientadvertise.services.service;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceGroupRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;
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

class BulkRemoteActionServiceAssignContentTest {

    private DeviceGroupRepository deviceGroupRepository;
    private DeviceRepository deviceRepository;
    private RemoteActionRepository remoteActionRepository;
    private PlaylistRepository playlistRepository;
    private BulkRemoteActionService service;

    @BeforeEach
    void setUp() {
        deviceGroupRepository = mock(DeviceGroupRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        remoteActionRepository = mock(RemoteActionRepository.class);
        playlistRepository = mock(PlaylistRepository.class);
        service = new BulkRemoteActionService(deviceGroupRepository, deviceRepository,
                remoteActionRepository, playlistRepository, new ObjectMapper(),
                mock(org.springframework.context.ApplicationEventPublisher.class));

        // Default group + 2 devices — every test uses the same group, only the payload
        // and playlist stubbing varies.
        var group = mock(DeviceGroup.class);
        when(group.getId()).thenReturn(1L);
        when(deviceGroupRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(group));
        // Build the device mocks BEFORE the outer when() — Mockito otherwise reads the
        // inner stubbings inside stubDevice(...) as unfinished stubs on the outer chain.
        var d1 = stubDevice(101L);
        var d2 = stubDevice(102L);
        when(deviceRepository.findByDeviceGroupIdAndDeletedAtIsNull(1L))
                .thenReturn(List.of(d1, d2));
    }

    /**
     * (a) Null payload — schema requires {@code {"playlistId":<id>}}, can't be omitted
     * for ASSIGN_CONTENT. Validation fires BEFORE the device loop, so no per-device
     * action is ever created.
     */
    @Test
    void nullPayload_throws400_andDevicesUntouched() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.issueToGroup(1L, "ASSIGN_CONTENT", null, "admin"));
        assertTrue(ex.getMessage().contains("ASSIGN_CONTENT requires payload"),
                "expected canonical schema message, got: " + ex.getMessage());

        // Crucial: NO RemoteAction was saved. The whole bulk request rejects pre-loop.
        verify(remoteActionRepository, never()).save(any());
    }

    /**
     * (b) Payload parses to JSON but lacks the {@code playlistId} field.
     */
    @Test
    void missingPlaylistIdField_throws400_andDevicesUntouched() {
        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.issueToGroup(1L, "ASSIGN_CONTENT", "{\"foo\":\"bar\"}", "admin"));
        assertTrue(ex.getMessage().contains("ASSIGN_CONTENT requires payload"),
                "expected canonical schema message, got: " + ex.getMessage());
        verify(remoteActionRepository, never()).save(any());
    }

    /**
     * (c) {@code playlistId} is present but no playlist with that id exists.
     */
    @Test
    void unknownPlaylist_throws400_withIdInMessage() {
        when(playlistRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.issueToGroup(1L, "ASSIGN_CONTENT", "{\"playlistId\":999}", "admin"));
        // Both clauses of the contract message are present: schema reminder + lookup failure.
        assertTrue(ex.getMessage().contains("ASSIGN_CONTENT requires payload"));
        assertTrue(ex.getMessage().contains("playlist 999 not found"),
                "expected lookup-failure clause naming the offending id, got: " + ex.getMessage());
        verify(remoteActionRepository, never()).save(any());
    }

    /**
     * (d) Soft-deleted playlist — same 404-treated-as-gone semantics as the rest of the
     * playlist API. The lookup uses {@code findByIdAndDeletedAtIsNull}, so a soft-deleted
     * row returns {@code Optional.empty()} and trips the same path as case (c).
     */
    @Test
    void softDeletedPlaylist_throws400() {
        // findByIdAndDeletedAtIsNull excludes soft-deleted rows by definition. Same
        // empty Optional means same exception path.
        when(playlistRepository.findByIdAndDeletedAtIsNull(50L)).thenReturn(Optional.empty());

        var ex = assertThrows(IllegalArgumentException.class, () ->
                service.issueToGroup(1L, "ASSIGN_CONTENT", "{\"playlistId\":50}", "admin"));
        assertTrue(ex.getMessage().contains("playlist 50 not found"));
        verify(remoteActionRepository, never()).save(any());
    }

    /**
     * (e) Valid payload — playlist exists and isn't soft-deleted. Every device gets a
     * {@link RemoteAction} whose {@code payload} is the exact JSON the FE submitted
     * (verbatim, no rewrite). This is the contract: validate the schema server-side,
     * pass the device-side payload through unchanged.
     */
    @Test
    void validPayload_allDevicesSucceed_payloadPropagatedVerbatim() {
        when(playlistRepository.findByIdAndDeletedAtIsNull(7L))
                .thenReturn(Optional.of(mock(Playlist.class)));
        // No PENDING dupes — every device save succeeds.
        when(remoteActionRepository.findPendingByDeviceAndType(any(), eq("ASSIGN_CONTENT")))
                .thenReturn(List.of());
        // Echo back the same RemoteAction. Avoid creating a NEW mock inside the
        // thenAnswer (calling when(newMock.getId()).thenReturn(...) inside a Mockito
        // answer interleaves stubbing contexts and produces "unfinished stubbing"
        // false positives on subsequent verifies).
        when(remoteActionRepository.save(any(RemoteAction.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        String payload = "{\"playlistId\":7}";
        var result = service.issueToGroup(1L, "ASSIGN_CONTENT", payload, "admin");

        assertEquals(2, result.totalDevices());
        assertEquals(2, result.succeededCount());
        assertEquals(0, result.skippedCount());
        assertEquals(0, result.failedCount());

        // Verify both devices received the EXACT FE-submitted JSON, not a re-serialization.
        var captor = org.mockito.ArgumentCaptor.forClass(RemoteAction.class);
        verify(remoteActionRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        for (RemoteAction a : captor.getAllValues()) {
            assertEquals(payload, a.getPayload(),
                    "per-device RemoteAction.payload must be the verbatim FE JSON");
            assertEquals("ASSIGN_CONTENT", a.getActionType());
        }
    }

    private static Device stubDevice(long id) {
        var d = mock(Device.class);
        when(d.getId()).thenReturn(id);
        return d;
    }
}
