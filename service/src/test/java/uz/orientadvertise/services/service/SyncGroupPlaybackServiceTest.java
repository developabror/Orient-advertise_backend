package uz.orientadvertise.services.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.content.SyncDispatcher;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.SyncGroup;
import uz.orientadvertise.services.domain.model.SyncGroupPlaybackOverride;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.SyncGroupPlaybackOverrideRepository;
import uz.orientadvertise.services.domain.repository.SyncGroupRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito-only unit tests. NOTE: every mock is built into a local BEFORE it is passed to
 * {@code when(...).thenReturn(...)} — invoking a stubbing helper inside an open {@code thenReturn}
 * trips Mockito's UnfinishedStubbing detection (the repo's documented nesting trap).
 */
class SyncGroupPlaybackServiceTest {

    private static final long GROUP_ID = 5L;
    private static final long PROJECT_ID = 3L;

    private SyncGroupRepository groupRepository;
    private DeviceRepository deviceRepository;
    private ContentAssignmentService assignmentService;
    private ContentVersionService contentVersionService;
    private PlaylistItemRepository playlistItemRepository;
    private SyncGroupPlaybackOverrideRepository overrideRepository;
    private SyncDispatcher syncDispatcher;
    private OperatorScopeResolver operatorScopeResolver;
    private SyncGroupPlaybackService service;

    @BeforeEach
    void setUp() {
        groupRepository = mock(SyncGroupRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        assignmentService = mock(ContentAssignmentService.class);
        contentVersionService = mock(ContentVersionService.class);
        playlistItemRepository = mock(PlaylistItemRepository.class);
        overrideRepository = mock(SyncGroupPlaybackOverrideRepository.class);
        syncDispatcher = mock(SyncDispatcher.class);
        operatorScopeResolver = mock(OperatorScopeResolver.class);
        service = new SyncGroupPlaybackService(groupRepository, deviceRepository, assignmentService,
                contentVersionService, playlistItemRepository, overrideRepository, syncDispatcher,
                operatorScopeResolver, Duration.ofSeconds(5));

        // Default: in-scope admin, group + project present. Build mocks first, then stub.
        SyncGroup g = group(GROUP_ID, PROJECT_ID);
        ScopedProjects scope = new ScopedProjects(null, Role.ADMIN, null, false);
        SyncDispatcher.DispatchResult dispatch = new SyncDispatcher.DispatchResult(2, 1, 0, 0, 0);
        when(operatorScopeResolver.resolve()).thenReturn(scope);
        when(groupRepository.findByIdWithProject(GROUP_ID)).thenReturn(Optional.of(g));
        when(syncDispatcher.dispatchSyncToDevices(any(), any())).thenReturn(dispatch);
    }

    // ----- jump: happy path -----

    @Test
    void jump_coherentGroup_upsertsOverride_anchorIsActivateMinusSlotStart_dispatchesToAllMembers() {
        Playlist playlist = playlist(100L, "Loop");
        ContentAssignment a = assignment(10L, 2, playlist);
        Device d1 = device(1L);
        Device d2 = device(2L);
        // Deliverable timeline 10s / 20s / 30s → slotStart 0 / 10000 / 30000, loop 60000.
        List<PlaylistItem> items = List.of(readyItem(0, 501L, 10), readyItem(1, 502L, 20), readyItem(2, 503L, 30));
        when(deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(List.of(d1, d2));
        when(assignmentService.resolveForDevice(eq(d1), any())).thenReturn(a);
        when(assignmentService.resolveForDevice(eq(d2), any())).thenReturn(a);
        when(contentVersionService.computeForAssignment(a)).thenReturn("v-abc");
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(100L)).thenReturn(items);
        when(overrideRepository.findBySyncGroupId(GROUP_ID)).thenReturn(Optional.empty());

        var result = service.jumpToIndex(GROUP_ID, 1, "operator-1");

        assertEquals(1, result.index());
        assertEquals(2, result.memberCount());
        // The core invariant: anchor == activateAt − slotStart[1] (slotStart[1] = 10s = 10000ms).
        assertEquals(result.activateAtEpochMs() - 10_000L, result.anchorEpochMs());

        ArgumentCaptor<SyncGroupPlaybackOverride> saved = ArgumentCaptor.forClass(SyncGroupPlaybackOverride.class);
        verify(overrideRepository).save(saved.capture());
        SyncGroupPlaybackOverride o = saved.getValue();
        assertEquals(GROUP_ID, o.getSyncGroupId());
        assertEquals(10L, o.getAssignmentId());
        assertEquals(2, o.getVersionNumber());
        assertEquals("v-abc", o.getContentVersion());
        assertEquals(1, o.getChosenIndex());
        assertEquals(o.getActivateAtEpochMs() - 10_000L, o.getAnchorEpochMs());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> ids = ArgumentCaptor.forClass(List.class);
        verify(syncDispatcher).dispatchSyncToDevices(ids.capture(), eq("sync-group-jump"));
        assertEquals(List.of(1L, 2L), ids.getValue());
    }

    @Test
    void jump_reJump_overwritesInPlace_noDuplicateRow() {
        Playlist playlist = playlist(100L, "Loop");
        ContentAssignment a = assignment(10L, 2, playlist);
        Device d1 = device(1L);
        List<PlaylistItem> items = List.of(readyItem(0, 501L, 10), readyItem(1, 502L, 20), readyItem(2, 503L, 30));
        when(deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(List.of(d1));
        when(assignmentService.resolveForDevice(eq(d1), any())).thenReturn(a);
        when(contentVersionService.computeForAssignment(a)).thenReturn("v-abc");
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(100L)).thenReturn(items);

        // First jump: no existing override → a new row is created and saved.
        when(overrideRepository.findBySyncGroupId(GROUP_ID)).thenReturn(Optional.empty());
        service.jumpToIndex(GROUP_ID, 0, "op");
        ArgumentCaptor<SyncGroupPlaybackOverride> first = ArgumentCaptor.forClass(SyncGroupPlaybackOverride.class);
        verify(overrideRepository).save(first.capture());
        SyncGroupPlaybackOverride existing = first.getValue();

        // Second jump: the same row is returned and overwritten in place (no new instance).
        when(overrideRepository.findBySyncGroupId(GROUP_ID)).thenReturn(Optional.of(existing));
        service.jumpToIndex(GROUP_ID, 2, "op");

        ArgumentCaptor<SyncGroupPlaybackOverride> both = ArgumentCaptor.forClass(SyncGroupPlaybackOverride.class);
        verify(overrideRepository, times(2)).save(both.capture());
        assertTrue(both.getAllValues().get(0) == both.getAllValues().get(1),
                "re-jump must overwrite the SAME override row, not create a duplicate");
        assertEquals(2, existing.getChosenIndex(), "re-jump updated the index in place");
    }

    @Test
    void jump_concurrentFirstJumpRace_uniqueViolation_mappedTo409Retry() {
        Playlist playlist = playlist(100L, "Loop");
        ContentAssignment a = assignment(10L, 2, playlist);
        Device d1 = device(1L);
        List<PlaylistItem> items = List.of(readyItem(0, 501L, 10), readyItem(1, 502L, 20));
        when(deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(List.of(d1));
        when(assignmentService.resolveForDevice(eq(d1), any())).thenReturn(a);
        when(contentVersionService.computeForAssignment(a)).thenReturn("v-abc");
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(100L)).thenReturn(items);
        when(overrideRepository.findBySyncGroupId(GROUP_ID)).thenReturn(Optional.empty());
        // A concurrent first-jump won the UNIQUE(sync_group_id) race → insert violates the constraint.
        when(overrideRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_sg_pb_override"));

        var ex = assertThrows(IllegalStateException.class, () -> service.jumpToIndex(GROUP_ID, 0, "op"));
        assertTrue(ex.getMessage().contains("concurrent jump"),
                "unique-constraint race must surface a clean retryable 409, not a 500");
    }

    // ----- jump: negative -----

    @Test
    void jump_emptyGroup_409() {
        when(deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(List.of());

        var ex = assertThrows(IllegalStateException.class, () -> service.jumpToIndex(GROUP_ID, 0, "op"));
        assertEquals("sync group has no member devices", ex.getMessage());
        verify(overrideRepository, never()).save(any());
        verify(syncDispatcher, never()).dispatchSyncToDevices(any(), any());
    }

    @Test
    void jump_membersResolveDifferentContent_409() {
        Device d1 = device(1L);
        Device d2 = device(2L);
        ContentAssignment a1 = assignment(10L, 2, playlist(100L, "A"));
        ContentAssignment a2 = assignment(11L, 2, playlist(200L, "B"));
        when(deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(List.of(d1, d2));
        when(assignmentService.resolveForDevice(eq(d1), any())).thenReturn(a1);
        when(assignmentService.resolveForDevice(eq(d2), any())).thenReturn(a2);

        var ex = assertThrows(IllegalStateException.class, () -> service.jumpToIndex(GROUP_ID, 0, "op"));
        assertTrue(ex.getMessage().contains("not content-coherent"));
        verify(overrideRepository, never()).save(any());
    }

    @Test
    void jump_memberWithNoPlaylist_409() {
        Device d1 = device(1L);
        Device d2 = device(2L);
        ContentAssignment a1 = assignment(10L, 2, playlist(100L, "A"));
        when(deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(List.of(d1, d2));
        when(assignmentService.resolveForDevice(eq(d1), any())).thenReturn(a1);
        when(assignmentService.resolveForDevice(eq(d2), any())).thenReturn(null);

        var ex = assertThrows(IllegalStateException.class, () -> service.jumpToIndex(GROUP_ID, 0, "op"));
        assertTrue(ex.getMessage().contains("not content-coherent"));
    }

    @Test
    void jump_indexOutOfRange_high_400() {
        coherentSingleMember(3);
        var ex = assertThrows(IllegalArgumentException.class, () -> service.jumpToIndex(GROUP_ID, 3, "op"));
        assertEquals("jump index 3 is out of range [0, 3)", ex.getMessage());
        verify(overrideRepository, never()).save(any());
    }

    @Test
    void jump_indexOutOfRange_negative_400() {
        coherentSingleMember(3);
        var ex = assertThrows(IllegalArgumentException.class, () -> service.jumpToIndex(GROUP_ID, -1, "op"));
        assertTrue(ex.getMessage().contains("out of range"));
    }

    @Test
    void jump_unknownOrOutOfScope_404() {
        ScopedProjects restricted = new ScopedProjects("op", Role.OPERATOR, List.of(999L), true);
        when(operatorScopeResolver.resolve()).thenReturn(restricted);
        assertThrows(ResourceNotFoundException.class, () -> service.jumpToIndex(GROUP_ID, 0, "op"));
        verify(deviceRepository, never()).findBySyncGroupIdAndDeletedAtIsNull(any());
    }

    // ----- getPlaybackView -----

    @Test
    void view_coherent_returnsItemsAndTimeline_noActiveJump() {
        Playlist playlist = playlist(100L, "Loop");
        ContentAssignment a = assignment(10L, 2, playlist);
        Device d1 = device(1L);
        List<PlaylistItem> items = List.of(readyItem(0, 501L, 10), readyItem(1, 502L, 20), readyItem(2, 503L, 30));
        when(deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(List.of(d1));
        when(assignmentService.resolveForDevice(eq(d1), any())).thenReturn(a);
        when(contentVersionService.computeForAssignment(a)).thenReturn("v-abc");
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(100L)).thenReturn(items);
        when(overrideRepository.findBySyncGroupId(GROUP_ID)).thenReturn(Optional.empty());

        var view = service.getPlaybackView(GROUP_ID);

        assertTrue(view.coherent());
        assertNull(view.reason());
        assertEquals(100L, view.playlistId());
        assertEquals("Loop", view.playlistName());
        assertEquals(60_000L, view.loopDurationMs());
        assertEquals(1, view.memberCount());
        assertEquals(3, view.items().size());
        assertEquals(0L, view.items().get(0).slotStartMs());
        assertEquals(10_000L, view.items().get(1).slotStartMs());
        assertEquals(30_000L, view.items().get(2).slotStartMs());
        assertNull(view.activeJump());
    }

    @Test
    void view_coherent_withMatchingOverride_reportsActiveJump() {
        Playlist playlist = playlist(100L, "Loop");
        ContentAssignment a = assignment(10L, 2, playlist);
        Device d1 = device(1L);
        List<PlaylistItem> items = List.of(readyItem(0, 501L, 10), readyItem(1, 502L, 20));
        SyncGroupPlaybackOverride o = mock(SyncGroupPlaybackOverride.class);
        when(o.getAssignmentId()).thenReturn(10L);
        when(o.getVersionNumber()).thenReturn(2);
        when(o.getContentVersion()).thenReturn("v-abc");
        when(o.getChosenIndex()).thenReturn(1);
        when(o.getActivateAtEpochMs()).thenReturn(1_700_000_000_000L);
        when(deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(List.of(d1));
        when(assignmentService.resolveForDevice(eq(d1), any())).thenReturn(a);
        when(contentVersionService.computeForAssignment(a)).thenReturn("v-abc");
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(100L)).thenReturn(items);
        when(overrideRepository.findBySyncGroupId(GROUP_ID)).thenReturn(Optional.of(o));

        var view = service.getPlaybackView(GROUP_ID);

        assertNotNull(view.activeJump());
        assertEquals(1, view.activeJump().index());
        assertEquals(1_700_000_000_000L, view.activeJump().activateAtEpochMs());
    }

    @Test
    void view_incoherent_returnsReasonAndEmptyItems() {
        Device d1 = device(1L);
        Device d2 = device(2L);
        ContentAssignment a1 = assignment(10L, 2, playlist(100L, "A"));
        ContentAssignment a2 = assignment(11L, 2, playlist(200L, "B"));
        when(deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(List.of(d1, d2));
        when(assignmentService.resolveForDevice(eq(d1), any())).thenReturn(a1);
        when(assignmentService.resolveForDevice(eq(d2), any())).thenReturn(a2);

        var view = service.getPlaybackView(GROUP_ID);

        assertFalse(view.coherent());
        assertEquals("members resolve different content", view.reason());
        assertTrue(view.items().isEmpty());
        assertEquals(2, view.memberCount());
    }

    @Test
    void view_emptyGroup_incoherentWithReason() {
        when(deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(List.of());

        var view = service.getPlaybackView(GROUP_ID);

        assertFalse(view.coherent());
        assertEquals("sync group has no member devices", view.reason());
        assertEquals(0, view.memberCount());
    }

    // ----- helpers (build-then-stub; never call these inside an open thenReturn) -----

    private void coherentSingleMember(int deliverableItems) {
        Playlist playlist = playlist(100L, "Loop");
        ContentAssignment a = assignment(10L, 2, playlist);
        Device d1 = device(1L);
        List<PlaylistItem> items = new ArrayList<>();
        for (int i = 0; i < deliverableItems; i++) {
            items.add(readyItem(i, 500L + i, 10));
        }
        when(deviceRepository.findBySyncGroupIdAndDeletedAtIsNull(GROUP_ID)).thenReturn(List.of(d1));
        when(assignmentService.resolveForDevice(eq(d1), any())).thenReturn(a);
        when(contentVersionService.computeForAssignment(a)).thenReturn("v-abc");
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(100L)).thenReturn(items);
        when(overrideRepository.findBySyncGroupId(GROUP_ID)).thenReturn(Optional.empty());
    }

    private static SyncGroup group(long id, long projectId) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        SyncGroup g = mock(SyncGroup.class);
        when(g.getId()).thenReturn(id);
        when(g.getProject()).thenReturn(project);
        return g;
    }

    private static Device device(long id) {
        Device d = mock(Device.class);
        when(d.getId()).thenReturn(id);
        return d;
    }

    private static Playlist playlist(long id, String name) {
        Playlist p = mock(Playlist.class);
        when(p.getId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        return p;
    }

    private static ContentAssignment assignment(long id, int version, Playlist playlist) {
        ContentAssignment a = mock(ContentAssignment.class);
        when(a.getId()).thenReturn(id);
        when(a.getVersionNumber()).thenReturn(version);
        when(a.getPlaylist()).thenReturn(playlist);
        return a;
    }

    private static PlaylistItem readyItem(int position, long fileId, int seconds) {
        ContentFile f = mock(ContentFile.class);
        when(f.getId()).thenReturn(fileId);
        when(f.getStatus()).thenReturn(ContentFile.Status.READY);
        when(f.getProcessedStorageKey()).thenReturn("key/" + fileId);
        when(f.getName()).thenReturn("file-" + fileId);
        when(f.getDurationSeconds()).thenReturn(seconds);
        PlaylistItem it = mock(PlaylistItem.class);
        when(it.getContentFile()).thenReturn(f);
        when(it.getPosition()).thenReturn(position);
        when(it.getDurationSeconds()).thenReturn(null);
        return it;
    }
}
