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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private PlaybackScheduleService playbackScheduleService;
    private PlaylistControlService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        assignmentService = mock(ContentAssignmentService.class);
        playlistItemRepository = mock(PlaylistItemRepository.class);
        remoteActionService = mock(RemoteActionService.class);
        playbackScheduleService = mock(PlaybackScheduleService.class);
        when(playbackScheduleService.find(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
        service = new PlaylistControlService(deviceRepository, assignmentService,
                playlistItemRepository, remoteActionService, playbackScheduleService);
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

    // ----- getActivePlaylist (the operator panel's source; VG-02) -----

    @Test
    void activePlaylist_listsDeliverableItemsReindexedFromZero() {
        Device d = mockDevice(20L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(20L)).thenReturn(Optional.of(d));
        ContentAssignment assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(200L);
        when(assignment.getVersionNumber()).thenReturn(1);
        Playlist p = mock(Playlist.class);
        when(p.getId()).thenReturn(90L);
        when(p.getName()).thenReturn("Mall Loop");
        when(assignment.getPlaylist()).thenReturn(p);
        // Raw playlist: [READY@0, TRANSCODING@1, READY@2] → the device is delivered 2 clips.
        PlaylistItem i0 = deliverableItem(0, 30);
        PlaylistItem i1 = nonDeliverableItem();
        PlaylistItem i2 = deliverableItem(2, 15);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(90L))
                .thenReturn(List.of(i0, i1, i2));
        when(assignmentService.resolveForDevice(eq(d), any())).thenReturn(assignment);

        var view = service.getActivePlaylist(20L);

        assertEquals(90L, view.playlistId());
        assertEquals("Mall Loop", view.playlistName());
        assertEquals(2, view.items().size());
        // The skipped item is gone and the indices are contiguous, so a JUMP on row 1 lands on
        // the clip the operator clicked; `position` still exposes the raw playlist slot.
        assertEquals(0, view.items().get(0).index());
        assertEquals(0, view.items().get(0).position());
        assertEquals(100L, view.items().get(0).fileId());
        assertEquals(30L, view.items().get(0).durationSeconds());
        assertEquals(1, view.items().get(1).index());
        assertEquals(2, view.items().get(1).position());
        assertEquals(15L, view.items().get(1).durationSeconds());
        assertEquals(45000L, view.loopDurationMs());
        assertFalse(view.scheduled());
    }

    @Test
    void activePlaylist_itemWithoutDuration_reportsTheDefaultSlotItActuallyPlays() {
        Device d = mockDevice(21L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(21L)).thenReturn(Optional.of(d));
        ContentAssignment assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(210L);
        when(assignment.getVersionNumber()).thenReturn(1);
        Playlist p = mock(Playlist.class);
        when(p.getId()).thenReturn(91L);
        when(assignment.getPlaylist()).thenReturn(p);
        PlaylistItem noDwell = deliverableItem(0, null);   // build mocks before stubbing with them
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(91L)).thenReturn(List.of(noDwell));
        when(assignmentService.resolveForDevice(eq(d), any())).thenReturn(assignment);

        var view = service.getActivePlaylist(21L);

        // Never 0/null: the device falls back to the 10 s default slot, so the panel shows it.
        assertEquals(10L, view.items().get(0).durationSeconds());
    }

    @Test
    void activePlaylist_jumpBoundMatchesTheListedRows() {
        Device d = mockDevice(22L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(22L)).thenReturn(Optional.of(d));
        ContentAssignment assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(220L);
        when(assignment.getVersionNumber()).thenReturn(1);
        Playlist p = mock(Playlist.class);
        when(p.getId()).thenReturn(92L);
        when(assignment.getPlaylist()).thenReturn(p);
        PlaylistItem r0 = deliverableItem(0, 10);
        PlaylistItem skipped = nonDeliverableItem();
        PlaylistItem r1 = deliverableItem(2, 10);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(92L))
                .thenReturn(List.of(r0, skipped, r1));
        when(assignmentService.resolveForDevice(eq(d), any())).thenReturn(assignment);

        int rows = service.getActivePlaylist(22L).items().size();

        // The last listed row is jumpable and one past it is not: the panel cannot offer a row
        // the range check would reject.
        service.issueControl(22L, ControlAction.JUMP, rows - 1, "ops");
        verify(remoteActionService).issue(eq(d), eq("PLAYLIST_CONTROL"), any(), eq("ops"), any());
        assertThrows(IllegalArgumentException.class,
                () -> service.issueControl(22L, ControlAction.JUMP, rows, "ops"));
    }

    @Test
    void activePlaylist_noAssignment_isEmptyNotAnError() {
        Device d = mockDevice(23L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(23L)).thenReturn(Optional.of(d));
        when(assignmentService.resolveForDevice(eq(d), any())).thenReturn(null);

        var view = service.getActivePlaylist(23L);

        assertEquals(23L, view.deviceId());
        assertNull(view.playlistId());
        assertTrue(view.items().isEmpty());
        assertEquals(0L, view.loopDurationMs());
        assertFalse(view.scheduled());
    }

    @Test
    void activePlaylist_unknownDevice_throws404() {
        when(deviceRepository.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getActivePlaylist(404L));
    }

    @Test
    void activePlaylist_anchorPresent_marksTheDeviceScheduled_andNeverCreatesOne() {
        Device d = mockDevice(24L);
        when(deviceRepository.findByIdAndDeletedAtIsNull(24L)).thenReturn(Optional.of(d));
        ContentAssignment assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(240L);
        when(assignment.getVersionNumber()).thenReturn(3);
        Playlist p = mock(Playlist.class);
        when(p.getId()).thenReturn(93L);
        when(assignment.getPlaylist()).thenReturn(p);
        PlaylistItem only = deliverableItem(0, 10);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(93L)).thenReturn(List.of(only));
        when(assignmentService.resolveForDevice(eq(d), any())).thenReturn(assignment);
        var anchor = mock(uz.orientadvertise.services.domain.model.PlaybackSyncSchedule.class);
        when(playbackScheduleService.find(240L, 3)).thenReturn(Optional.of(anchor));

        var view = service.getActivePlaylist(24L);

        assertTrue(view.scheduled(), "an existing anchor means synchronised playback");
        verify(playbackScheduleService).find(240L, 3);
        // Reading the panel must never arm a cut-over: getOrCreate writes, find does not.
        verify(playbackScheduleService, org.mockito.Mockito.never()).getOrCreate(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    private static PlaylistItem deliverableItem() {
        PlaylistItem item = mock(PlaylistItem.class);
        ContentFile f = mock(ContentFile.class);
        when(f.getStatus()).thenReturn(ContentFile.Status.READY);
        when(f.getProcessedStorageKey()).thenReturn("key.mp4");
        when(item.getContentFile()).thenReturn(f);
        return item;
    }

    /** A deliverable item at a raw playlist slot, with an optional per-item dwell. */
    private static PlaylistItem deliverableItem(int position, Integer durationSeconds) {
        ContentFile f = mock(ContentFile.class);
        when(f.getStatus()).thenReturn(ContentFile.Status.READY);
        when(f.getProcessedStorageKey()).thenReturn("key.mp4");
        when(f.getId()).thenReturn(100L + position);
        when(f.getDurationSeconds()).thenReturn(null);   // dwell comes from the item, if at all
        PlaylistItem item = mock(PlaylistItem.class);
        when(item.getContentFile()).thenReturn(f);
        when(item.getPosition()).thenReturn(position);
        when(item.getDurationSeconds()).thenReturn(durationSeconds);
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
