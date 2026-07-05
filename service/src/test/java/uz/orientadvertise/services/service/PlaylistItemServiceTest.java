package uz.orientadvertise.services.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlaylistItemServiceTest {

    private PlaylistRepository playlistRepository;
    private PlaylistItemRepository itemRepository;
    private ContentFileRepository contentFileRepository;
    private ApplicationEventPublisher eventPublisher;
    private OperatorScopeResolver operatorScopeResolver;
    private PlaylistItemService service;
    private Playlist playlist;

    @BeforeEach
    void setUp() {
        playlistRepository = mock(PlaylistRepository.class);
        itemRepository = mock(PlaylistItemRepository.class);
        contentFileRepository = mock(ContentFileRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        operatorScopeResolver = mock(OperatorScopeResolver.class);
        // Unrestricted scope: excludes() returns false without touching the playlist's project graph.
        when(operatorScopeResolver.resolve()).thenReturn(
                new OperatorScopeResolver.ScopedProjects(null, null, null, false));
        service = new PlaylistItemService(playlistRepository, itemRepository,
                contentFileRepository, eventPublisher, operatorScopeResolver);
        playlist = new Playlist(new Project("P", null), "PL", null);
        when(playlistRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(playlist));
    }

    // ----- addItem -----

    @Test
    void addItem_appendsWhenPositionNull() {
        var content = readyContent(100L);
        // Build helper-mocks BEFORE the outer when() — Mockito will otherwise read
        // their internal stubbings as unfinished stubbings on the outer chain.
        var i0 = stubItem(10L, 0);
        var i1 = stubItem(11L, 1);
        when(contentFileRepository.findById(100L)).thenReturn(Optional.of(content));
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L)).thenReturn(List.of(i0, i1));
        when(itemRepository.save(any(PlaylistItem.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.addItem(1L, 100L, null, null);

        // Append: no shift, target position == currentSize
        verify(itemRepository, never()).shiftPositionsForInsert(anyLong(), anyInt());
        assertEquals(2, result.getPosition());
    }

    @Test
    void addItem_insertsAtMiddle_shiftsExisting() {
        var content = readyContent(100L);
        var i0 = stubItem(10L, 0);
        var i1 = stubItem(11L, 1);
        var i2 = stubItem(12L, 2);
        when(contentFileRepository.findById(100L)).thenReturn(Optional.of(content));
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L)).thenReturn(List.of(i0, i1, i2));
        when(itemRepository.save(any(PlaylistItem.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.addItem(1L, 100L, 1, 30);

        verify(itemRepository).shiftPositionsForInsert(1L, 1);
        assertEquals(1, result.getPosition());
        assertEquals(30, result.getDurationSeconds());
    }

    @Test
    void addItem_nonReadyContent_throws400() {
        var content = mock(ContentFile.class);
        when(content.getStatus()).thenReturn(ContentFile.Status.TRANSCODING);
        when(content.getDeletedAt()).thenReturn(null);
        when(contentFileRepository.findById(100L)).thenReturn(Optional.of(content));

        assertThrows(IllegalArgumentException.class, () ->
                service.addItem(1L, 100L, null, null));

        // Crucial: no shift happens before bail.
        verify(itemRepository, never()).shiftPositionsForInsert(anyLong(), anyInt());
    }

    @Test
    void addItem_softDeletedContent_throws404() {
        var content = mock(ContentFile.class);
        when(content.getDeletedAt()).thenReturn(java.time.Instant.now());
        when(contentFileRepository.findById(100L)).thenReturn(Optional.of(content));

        assertThrows(ResourceNotFoundException.class, () ->
                service.addItem(1L, 100L, null, null));
    }

    @Test
    void addItem_positionOutOfRange_throws400() {
        var content = readyContent(100L);
        var i0 = stubItem(10L, 0);
        var i1 = stubItem(11L, 1);
        when(contentFileRepository.findById(100L)).thenReturn(Optional.of(content));
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L)).thenReturn(List.of(i0, i1));

        // currentSize=2, valid range [0, 2]; 3 is out-of-range.
        assertThrows(IllegalArgumentException.class, () ->
                service.addItem(1L, 100L, 3, null));
        // Negative is rejected too.
        assertThrows(IllegalArgumentException.class, () ->
                service.addItem(1L, 100L, -1, null));
    }

    // ----- removeItem -----

    @Test
    void removeItem_compactsPositions() {
        var item = stubItem(10L, 2);
        when(item.getPlaylist()).thenReturn(playlist);
        // Match by referencing the same playlist via id — playlist.getId() is null on
        // the unmocked entity, so the loadItemForPlaylist guard treats them as equal
        // (both null). Using the explicit id-equality path with mocked playlist:
        var playlistMock = scopedPlaylistMock(1L);
        when(item.getPlaylist()).thenReturn(playlistMock);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        service.removeItem(1L, 10L);

        verify(itemRepository).delete(item);
        verify(itemRepository).compactPositionsAfterRemoval(1L, 2);
    }

    @Test
    void removeItem_itemBelongsToOtherPlaylist_throws404() {
        var otherPlaylist = mock(Playlist.class);
        when(otherPlaylist.getId()).thenReturn(99L);
        var item = mock(PlaylistItem.class);
        when(item.getPlaylist()).thenReturn(otherPlaylist);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        assertThrows(ResourceNotFoundException.class, () ->
                service.removeItem(1L, 10L));
        verify(itemRepository, never()).delete(any(PlaylistItem.class));
    }

    // ----- moveItem -----

    @Test
    void moveItem_movingDown_shiftsUp() {
        var item = ownedItem(10L, 1);
        var sib0 = ownedItem(20L, 0);
        var sib2 = ownedItem(21L, 2);
        var sib3 = ownedItem(22L, 3);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L))
                .thenReturn(List.of(sib0, item, sib2, sib3));

        service.moveItem(1L, 10L, 3);

        verify(itemRepository).shiftPositionsUp(1L, 1, 3);
        assertEquals(3, item.getPosition());
    }

    @Test
    void moveItem_movingUp_shiftsDown() {
        var item = ownedItem(10L, 3);
        var sib0 = ownedItem(20L, 0);
        var sib1 = ownedItem(21L, 1);
        var sib2 = ownedItem(22L, 2);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L))
                .thenReturn(List.of(sib0, sib1, sib2, item));

        service.moveItem(1L, 10L, 1);

        verify(itemRepository).shiftPositionsDown(1L, 1, 3);
        assertEquals(1, item.getPosition());
    }

    @Test
    void moveItem_toPositionOutOfRange_throws400() {
        var item = ownedItem(10L, 0);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L)).thenReturn(List.of(item));

        // currentSize=1, valid range is [0, 0]; 1 is out of range.
        assertThrows(IllegalArgumentException.class, () ->
                service.moveItem(1L, 10L, 1));
    }

    @Test
    void moveItem_intermediateStateIsSentinel_thenTarget() {
        // Atomic-swap-style proof: when one item moves, it goes through position -1
        // BEFORE it lands on the target. Captures the order of saves to prove the
        // sentinel is observed mid-transaction (any DB-level UNIQUE check at this
        // moment would not collide with another item on the target position).
        var moved = ownedItem(10L, 0);
        var other = ownedItem(20L, 1);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(moved));
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L)).thenReturn(List.of(moved, other));

        var observed = new ArrayList<Integer>();
        when(itemRepository.saveAndFlush(any(PlaylistItem.class))).thenAnswer(inv -> {
            observed.add(((PlaylistItem) inv.getArgument(0)).getPosition());
            return inv.getArgument(0);
        });
        when(itemRepository.save(any(PlaylistItem.class))).thenAnswer(inv -> {
            observed.add(((PlaylistItem) inv.getArgument(0)).getPosition());
            return inv.getArgument(0);
        });

        service.moveItem(1L, 10L, 1);

        // Sequence: park at -1 (saveAndFlush), then place at 1 (save).
        assertEquals(List.of(-1, 1), observed);
        var inOrder = inOrder(itemRepository);
        inOrder.verify(itemRepository).saveAndFlush(any(PlaylistItem.class));
        inOrder.verify(itemRepository).shiftPositionsUp(1L, 0, 1);
        inOrder.verify(itemRepository).save(any(PlaylistItem.class));
    }

    // ----- reorderAll -----

    @Test
    void reorderAll_swapTwoItems_atomicViaNegativeSentinels() {
        // Two-item swap: 10 was at 0, 20 was at 1. After: 10 at 1, 20 at 0. The
        // database NEVER sees both items on the same position because the first phase
        // parks each on a unique negative sentinel before any positive position is
        // re-assigned.
        var item10 = ownedItem(10L, 0);
        var item20 = ownedItem(20L, 1);
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L))
                .thenReturn(List.of(item10, item20));

        var allObservedPositions = new ArrayList<int[]>();
        when(itemRepository.saveAllAndFlush(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<PlaylistItem> batch = (List<PlaylistItem>) inv.getArgument(0);
            allObservedPositions.add(batch.stream().mapToInt(PlaylistItem::getPosition).toArray());
            return batch;
        });

        service.reorderAll(1L, List.of(20L, 10L));

        // First saveAllAndFlush parks at sentinels: -1 and -2 (one per item, never colliding).
        // Second saveAllAndFlush places at final positions 0 and 1 (no collision because
        // sentinels held the "real" positions until both moved off them).
        assertEquals(2, allObservedPositions.size());
        var sentinels = allObservedPositions.get(0);
        assertEquals(-1, sentinels[0]);
        assertEquals(-2, sentinels[1]);
        // After: item10 → 1 (because its target id appeared second in the supplied list),
        // item20 → 0.
        assertEquals(1, item10.getPosition());
        assertEquals(0, item20.getPosition());
    }

    @Test
    void reorderAll_extraId_throws400() {
        var i10 = ownedItem(10L, 0);
        var i20 = ownedItem(20L, 1);
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L)).thenReturn(List.of(i10, i20));

        // 30 isn't part of the playlist.
        assertThrows(IllegalArgumentException.class, () ->
                service.reorderAll(1L, List.of(10L, 20L, 30L)));
        verify(itemRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void reorderAll_missingId_throws400() {
        var i10 = ownedItem(10L, 0);
        var i20 = ownedItem(20L, 1);
        var i30 = ownedItem(30L, 2);
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L)).thenReturn(List.of(i10, i20, i30));

        // Missing 30L.
        assertThrows(IllegalArgumentException.class, () ->
                service.reorderAll(1L, List.of(10L, 20L)));
    }

    @Test
    void reorderAll_duplicateIdInRequest_throws400() {
        var i10 = ownedItem(10L, 0);
        var i20 = ownedItem(20L, 1);
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L)).thenReturn(List.of(i10, i20));

        // Duplicate id – the set-equality check would pass but size mismatch trips it.
        assertThrows(IllegalArgumentException.class, () ->
                service.reorderAll(1L, List.of(10L, 10L)));
    }

    // ----- setDuration -----

    @Test
    void setDuration_setsValueAndReturnsItem() {
        var item = ownedItem(10L, 0);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        var result = service.setDuration(1L, 10L, 45);

        verify(item).setDurationSeconds(45);
        // Sanity: the service returns the same managed instance the setter ran on.
        org.junit.jupiter.api.Assertions.assertSame(item, result);
    }

    @Test
    void setDuration_nullClearsOverride() {
        var item = ownedItem(10L, 0);
        // Pre-existing override that the call must wipe.
        when(item.getDurationSeconds()).thenReturn(30);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        service.setDuration(1L, 10L, null);

        verify(item).setDurationSeconds(null);
    }

    @Test
    void setDuration_zero_throws400() {
        var item = ownedItem(10L, 0);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        assertThrows(IllegalArgumentException.class, () ->
                service.setDuration(1L, 10L, 0));
        // The setter never fires for an invalid input.
        verify(item, never()).setDurationSeconds(any());
    }

    @Test
    void setDuration_overOneDay_throws400() {
        var item = ownedItem(10L, 0);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        assertThrows(IllegalArgumentException.class, () ->
                service.setDuration(1L, 10L, 86401));
        verify(item, never()).setDurationSeconds(any());
    }

    @Test
    void setDuration_unknownItem_throws404() {
        when(itemRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.setDuration(1L, 999L, 30));
    }

    @Test
    void setDuration_itemBelongsToOtherPlaylist_throws404() {
        var otherPlaylist = mock(uz.orientadvertise.services.domain.model.Playlist.class);
        when(otherPlaylist.getId()).thenReturn(99L);
        var item = mock(PlaylistItem.class);
        when(item.getPlaylist()).thenReturn(otherPlaylist);
        when(itemRepository.findById(10L)).thenReturn(Optional.of(item));

        assertThrows(ResourceNotFoundException.class, () ->
                service.setDuration(1L, 10L, 30));
    }

    @Test
    void setDuration_softDeletedPlaylist_throws404() {
        // Bypass the @BeforeEach stub by overriding it for this id.
        when(playlistRepository.findByIdAndDeletedAtIsNull(eq(1L))).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.setDuration(1L, 10L, 30));
        // Importantly, we never even look up the item.
        verify(itemRepository, never()).findById(any());
    }

    @Test
    void reorderAll_unknownPlaylist_throws404() {
        when(playlistRepository.findByIdAndDeletedAtIsNull(eq(99L))).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () ->
                service.reorderAll(99L, List.of()));
    }

    // ----- helpers -----

    private static ContentFile readyContent(long id) {
        var c = mock(ContentFile.class);
        when(c.getId()).thenReturn(id);
        when(c.getStatus()).thenReturn(ContentFile.Status.READY);
        when(c.getDeletedAt()).thenReturn(null);
        return c;
    }

    /** A simple PlaylistItem mock storing position in a holder so setPosition is observable. */
    private static PlaylistItem stubItem(long id, int position) {
        var item = mock(PlaylistItem.class);
        when(item.getId()).thenReturn(id);
        var holder = new int[]{position};
        when(item.getPosition()).thenAnswer(inv -> holder[0]);
        org.mockito.Mockito.doAnswer(inv -> { holder[0] = inv.getArgument(0); return null; })
                .when(item).setPosition(anyInt());
        return item;
    }

    /** A PlaylistItem whose getPlaylist().getId() == 1L — the test's playlist. */
    private PlaylistItem ownedItem(long id, int position) {
        var item = stubItem(id, position);
        // Build the playlist mock (and its inner project mock) BEFORE the outer when() so
        // Mockito doesn't read the inner stubbings as an unfinished outer stubbing.
        var pl = scopedPlaylistMock(1L);
        when(item.getPlaylist()).thenReturn(pl);
        return item;
    }

    /** A Playlist mock with a non-null project so the (eagerly-evaluated) scope-guard
     *  argument {@code getProject().getId()} doesn't NPE. Scope is unrestricted in this
     *  test, so the actual project id is irrelevant. */
    private static Playlist scopedPlaylistMock(Long playlistId) {
        var pl = mock(Playlist.class);
        when(pl.getId()).thenReturn(playlistId);
        var project = mock(Project.class);
        when(project.getId()).thenReturn(1000L);
        when(pl.getProject()).thenReturn(project);
        return pl;
    }

    @SuppressWarnings("unused")
    private static <K, V> Map<K, V> asMap(K k1, V v1) {
        var m = new HashMap<K, V>();
        m.put(k1, v1);
        return m;
    }

    // ===== Item 1: PlaylistReorderedEvent publication on every mutation =====

    @Test
    void addItem_success_publishesReorderedEvent() {
        var content = readyContent(100L);
        when(contentFileRepository.findById(100L)).thenReturn(Optional.of(content));
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L)).thenReturn(List.of());
        when(itemRepository.save(any(PlaylistItem.class))).thenAnswer(inv -> inv.getArgument(0));

        service.addItem(1L, 100L, null, null);

        verifyEventPublishedFor(1L);
    }

    @Test
    void addItem_notReadyFile_throws_andPublishesNoEvent() {
        var content = mock(ContentFile.class);
        when(content.getStatus()).thenReturn(ContentFile.Status.TRANSCODING);
        when(contentFileRepository.findById(100L)).thenReturn(Optional.of(content));

        assertThrows(IllegalArgumentException.class,
                () -> service.addItem(1L, 100L, null, null));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void removeItem_success_publishesReorderedEvent() {
        var item = ownedItem(50L, 2);
        when(itemRepository.findById(50L)).thenReturn(Optional.of(item));

        service.removeItem(1L, 50L);

        verifyEventPublishedFor(1L);
    }

    @Test
    void moveItem_success_publishesReorderedEvent() {
        var item = ownedItem(60L, 0);
        // Build helper-mocks BEFORE the outer when() — nesting them inside .thenReturn(...)
        // would re-enter Mockito's stubbing state and trigger UnfinishedStubbingException.
        var sibling1 = stubItem(61L, 1);
        var sibling2 = stubItem(62L, 2);
        when(itemRepository.findById(60L)).thenReturn(Optional.of(item));
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L))
                .thenReturn(List.of(item, sibling1, sibling2));
        when(itemRepository.save(any(PlaylistItem.class))).thenAnswer(inv -> inv.getArgument(0));

        service.moveItem(1L, 60L, 2);

        verifyEventPublishedFor(1L);
    }

    @Test
    void reorderAll_success_publishesReorderedEvent() {
        var i1 = stubItem(70L, 0);
        var i2 = stubItem(71L, 1);
        var i3 = stubItem(72L, 2);
        var pre = new ArrayList<>(List.of(i1, i2, i3));
        var post = new ArrayList<>(List.of(i3, i1, i2));
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L))
                .thenReturn(pre)
                .thenReturn(post); // post-shuffle read

        service.reorderAll(1L, List.of(72L, 70L, 71L));

        verifyEventPublishedFor(1L);
    }

    @Test
    void reorderAll_idMismatch_throws_andPublishesNoEvent() {
        var i1 = stubItem(80L, 0);
        var i2 = stubItem(81L, 1);
        var existing = new ArrayList<>(List.of(i1, i2));
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(1L)).thenReturn(existing);

        assertThrows(IllegalArgumentException.class,
                () -> service.reorderAll(1L, List.of(80L, 999L)));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void setDuration_success_publishesReorderedEvent() {
        var item = ownedItem(90L, 0);
        when(itemRepository.findById(90L)).thenReturn(Optional.of(item));

        service.setDuration(1L, 90L, 12);

        verifyEventPublishedFor(1L);
    }

    @Test
    void setDuration_outOfRange_throws_andPublishesNoEvent() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setDuration(1L, 90L, 0));

        verifyNoInteractions(eventPublisher);
    }

    private void verifyEventPublishedFor(Long expectedPlaylistId) {
        var captor = ArgumentCaptor.forClass(PlaylistReorderedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(expectedPlaylistId, captor.getValue().playlistId());
    }
}
