package uz.orientadvertise.services.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.service.PlaylistControlService.ControlAction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaylistControlServiceTest {

    private DeviceRepository deviceRepository;
    private ContentAssignmentService assignmentService;
    private PlaylistItemRepository playlistItemRepository;
    private RemoteActionService remoteActionService;
    private PlaylistControlService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        assignmentService = mock(ContentAssignmentService.class);
        playlistItemRepository = mock(PlaylistItemRepository.class);
        remoteActionService = mock(RemoteActionService.class);
        service = new PlaylistControlService(deviceRepository, assignmentService,
                playlistItemRepository, remoteActionService);
    }

    @Test
    void unknownDevice_throws404() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.issueControl(404L, ControlAction.NEXT, null, "operator"));
    }

    @Test
    void noAssignedPlaylist_throwsBadRequest() {
        Device d = mockDevice(1L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(d));
        when(assignmentService.resolveForDevice(eq(d), any())).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.issueControl(1L, ControlAction.NEXT, null, "operator"));
    }

    @Test
    void next_issuesActionWithTenMinuteTimeout() {
        Device d = mockDevice(2L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(d));
        var assignment = mockAssignmentWithPlaylist(20L, 5);
        when(assignmentService.resolveForDevice(eq(d), any())).thenReturn(assignment);
        var stubAction = mock(RemoteAction.class);
        when(remoteActionService.issue(eq(d), eq("PLAYLIST_CONTROL"), any(), eq("alice"),
                eq(Duration.ofMinutes(10)))).thenReturn(stubAction);

        var result = service.issueControl(2L, ControlAction.NEXT, null, "alice");

        assertEquals(stubAction, result);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(remoteActionService).issue(eq(d), eq("PLAYLIST_CONTROL"), payloadCaptor.capture(),
                eq("alice"), eq(Duration.ofMinutes(10)));
        assertTrue(payloadCaptor.getValue().contains("\"action\":\"NEXT\""),
                "payload should encode action: " + payloadCaptor.getValue());
    }

    @Test
    void prev_encodesActionInPayload() {
        Device d = mockDevice(3L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(d));
        var assignment = mockAssignmentWithPlaylist(30L, 3);
        when(assignmentService.resolveForDevice(eq(d), any())).thenReturn(assignment);

        service.issueControl(3L, ControlAction.PREV, null, "bob");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(remoteActionService).issue(eq(d), eq("PLAYLIST_CONTROL"), payloadCaptor.capture(),
                eq("bob"), any());
        assertTrue(payloadCaptor.getValue().contains("\"action\":\"PREV\""));
    }

    @Test
    void jump_validPosition_includesPositionInPayload() {
        Device d = mockDevice(4L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(d));
        var assignment = mockAssignmentWithPlaylist(40L, 5); // 5 items, valid positions [0..4]
        when(assignmentService.resolveForDevice(eq(d), any())).thenReturn(assignment);

        service.issueControl(4L, ControlAction.JUMP, 2, "ops");

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(remoteActionService).issue(eq(d), eq("PLAYLIST_CONTROL"), payloadCaptor.capture(),
                eq("ops"), any());
        assertTrue(payloadCaptor.getValue().contains("\"action\":\"JUMP\""));
        assertTrue(payloadCaptor.getValue().contains("\"position\":2"));
    }

    @Test
    void jump_missingPosition_throwsBadRequest() {
        Device d = mockDevice(5L);
        var assignment = mockAssignmentWithPlaylist(50L, 5);
        when(deviceRepository.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(d));
        when(assignmentService.resolveForDevice(eq(d), any())).thenReturn(assignment);

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.issueControl(5L, ControlAction.JUMP, null, "ops"));
        assertTrue(ex.getMessage().contains("position"));
    }

    @Test
    void jump_positionOutOfRange_throwsBadRequest_andDoesNotIssueAction() {
        Device d = mockDevice(6L);
        var assignment = mockAssignmentWithPlaylist(60L, 3); // valid: [0..2]
        when(deviceRepository.findByIdAndDeletedAtIsNull(6L)).thenReturn(Optional.of(d));
        when(assignmentService.resolveForDevice(eq(d), any())).thenReturn(assignment);

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.issueControl(6L, ControlAction.JUMP, 5, "ops"));
        assertTrue(ex.getMessage().contains("out of range"));

        // Critical: validation runs BEFORE remoteActionService.issue
        verify(remoteActionService, org.mockito.Mockito.never())
                .issue(any(), any(), any(), any(), any());
    }

    @Test
    void jump_negativePosition_throwsBadRequest() {
        Device d = mockDevice(7L);
        var assignment = mockAssignmentWithPlaylist(70L, 5);
        when(deviceRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(d));
        when(assignmentService.resolveForDevice(eq(d), any())).thenReturn(assignment);

        assertThrows(IllegalArgumentException.class,
                () -> service.issueControl(7L, ControlAction.JUMP, -1, "ops"));
    }

    private static Device mockDevice(Long id) {
        Device d = mock(Device.class);
        when(d.getId()).thenReturn(id);
        return d;
    }

    private ContentAssignment mockAssignmentWithPlaylist(Long playlistId, int itemCount) {
        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist p = mock(Playlist.class);
        when(p.getId()).thenReturn(playlistId);
        when(assignment.getPlaylist()).thenReturn(p);
        var items = new java.util.ArrayList<PlaylistItem>();
        for (int i = 0; i < itemCount; i++) {
            items.add(deliverableItem());   // JUMP now counts the DELIVERABLE subset
        }
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(playlistId))
                .thenReturn(List.copyOf(items));
        return assignment;
    }

    @Test
    void jump_addressesDeliverableSubset_notRawItemCount() {
        // Playlist [READY, non-READY, READY, READY] → 3 deliverable → valid index range [0..2].
        Device d = mockDevice(8L);
        ContentAssignment assignment = mock(ContentAssignment.class);
        Playlist p = mock(Playlist.class);
        when(p.getId()).thenReturn(80L);
        when(assignment.getPlaylist()).thenReturn(p);
        PlaylistItem d0 = deliverableItem();   // build mocks first (helpers call when() internally)
        PlaylistItem d1 = nonDeliverableItem();
        PlaylistItem d2 = deliverableItem();
        PlaylistItem d3 = deliverableItem();
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(80L)).thenReturn(List.of(d0, d1, d2, d3));
        when(deviceRepository.findByIdAndDeletedAtIsNull(8L)).thenReturn(Optional.of(d));
        when(assignmentService.resolveForDevice(eq(d), any())).thenReturn(assignment);

        // JUMP 2 → the 3rd DELIVERABLE clip → accepted (issues the action).
        service.issueControl(8L, ControlAction.JUMP, 2, "ops");
        verify(remoteActionService).issue(eq(d), eq("PLAYLIST_CONTROL"), any(), eq("ops"), any());

        // JUMP 3 == raw item count but only 3 deliverable (valid [0..2]) → rejected, not issued.
        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.issueControl(8L, ControlAction.JUMP, 3, "ops"));
        assertTrue(ex.getMessage().contains("out of range"));
    }

    private static PlaylistItem deliverableItem() {
        PlaylistItem item = mock(PlaylistItem.class);
        ContentFile f = mock(ContentFile.class);
        when(f.getStatus()).thenReturn(ContentFile.Status.READY);
        when(f.getProcessedStorageKey()).thenReturn("key.mp4");
        when(item.getContentFile()).thenReturn(f);
        return item;
    }

    private static PlaylistItem nonDeliverableItem() {
        PlaylistItem item = mock(PlaylistItem.class);
        ContentFile f = mock(ContentFile.class);
        when(f.getStatus()).thenReturn(ContentFile.Status.TRANSCODING);
        when(item.getContentFile()).thenReturn(f);
        return item;
    }
}
