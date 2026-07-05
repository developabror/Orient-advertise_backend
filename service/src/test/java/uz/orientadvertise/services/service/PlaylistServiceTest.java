package uz.orientadvertise.services.service;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

class PlaylistServiceTest {

    private PlaylistRepository playlistRepository;
    private PlaylistItemRepository itemRepository;
    private PlaylistService service;

    @BeforeEach
    void setUp() {
        playlistRepository = mock(PlaylistRepository.class);
        itemRepository = mock(PlaylistItemRepository.class);
        service = new PlaylistService(playlistRepository, itemRepository);
    }

    @Test
    void addItem_shiftsExistingItemsAndInserts() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        when(playlistRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(playlist));
        var contentFile = new ContentFile(new Project("P", null), "f", "video/mp4", 100, "k", null);
        var savedItem = new PlaylistItem(playlist, contentFile, 0, 10);
        when(itemRepository.save(any(PlaylistItem.class))).thenReturn(savedItem);

        var result = service.addItem(1L, contentFile, 0, 10);

        verify(itemRepository).shiftPositionsForInsert(1L, 0);
        assertEquals(0, result.getPosition());
    }

    @Test
    void addItem_playlistNotFound_throws() {
        when(playlistRepository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () ->
                service.addItem(99L, null, 0, null));
    }

    @Test
    void addItem_softDeletedContent_throws404() {
        // Defense-in-depth check: even if the caller fetched the file just before a
        // concurrent soft-delete completed, addItem must refuse so the file does not
        // re-appear inside a playlist after disappearing from listings/detail.
        var playlist = new Playlist(new Project("P", null), "PL", null);
        when(playlistRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(playlist));
        var contentFile = new ContentFile(new Project("P", null), "f", "video/mp4", 100, "k", null);
        contentFile.softDelete();

        assertThrows(ResourceNotFoundException.class, () ->
                service.addItem(1L, contentFile, 0, 10));

        // Crucially, no shift happens — we bail BEFORE mutating playlist positions.
        verify(itemRepository, never()).shiftPositionsForInsert(anyLong(), anyInt());
    }

    @Test
    void removeItem_compactsPositions() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var item = new PlaylistItem(playlist, null, 2, null);
        when(itemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(playlistRepository.findByIdAndDeletedAtIsNull(anyLong())).thenReturn(Optional.of(playlist));

        service.removeItem(5L, 1L);

        verify(itemRepository).delete(item);
        verify(itemRepository).compactPositionsAfterRemoval(5L, 2);
    }

    @Test
    void reorderItem_movingDown_shiftsUp() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var item = new PlaylistItem(playlist, null, 1, null);
        when(itemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(playlistRepository.findByIdAndDeletedAtIsNull(anyLong())).thenReturn(Optional.of(playlist));

        service.reorderItem(5L, 1L, 3);

        // Moving from 1→3: shift items in (1, 3] up
        verify(itemRepository).shiftPositionsUp(5L, 1, 3);
        assertEquals(3, item.getPosition());
    }

    @Test
    void reorderItem_movingUp_shiftsDown() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var item = new PlaylistItem(playlist, null, 3, null);
        when(itemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(playlistRepository.findByIdAndDeletedAtIsNull(anyLong())).thenReturn(Optional.of(playlist));

        service.reorderItem(5L, 1L, 1);

        // Moving from 3→1: shift items in [1, 3) down
        verify(itemRepository).shiftPositionsDown(5L, 1, 3);
        assertEquals(1, item.getPosition());
    }

    @Test
    void reorderItem_samePosition_noOp() {
        var item = new PlaylistItem(null, null, 2, null);
        when(itemRepository.findById(1L)).thenReturn(Optional.of(item));

        service.reorderItem(5L, 1L, 2);

        verify(itemRepository, never()).shiftPositionsUp(anyLong(), anyInt(), anyInt());
        verify(itemRepository, never()).shiftPositionsDown(anyLong(), anyInt(), anyInt());
    }

    @Test
    void reorderAll_reassignsAllPositions() {
        var playlist = new Playlist(new Project("P", null), "PL", null);
        var item1 = mockItem(10L, 0);
        var item2 = mockItem(20L, 1);
        var item3 = mockItem(30L, 2);
        when(itemRepository.findByPlaylistIdOrderByPositionAsc(5L)).thenReturn(List.of(item1, item2, item3));
        when(playlistRepository.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(playlist));

        // Reverse the order: 30, 10, 20
        service.reorderAll(5L, List.of(30L, 10L, 20L));

        assertEquals(1, item1.getPosition());  // was 0, now 1
        assertEquals(2, item2.getPosition());  // was 1, now 2
        assertEquals(0, item3.getPosition());  // was 2, now 0
    }

    private PlaylistItem mockItem(Long id, int position) {
        var item = mock(PlaylistItem.class);
        when(item.getId()).thenReturn(id);
        when(item.getPosition()).thenReturn(position);
        // Allow setPosition to work
        var posHolder = new int[]{position};
        org.mockito.Mockito.doAnswer(inv -> { posHolder[0] = inv.getArgument(0); return null; })
                .when(item).setPosition(anyInt());
        when(item.getPosition()).thenAnswer(inv -> posHolder[0]);
        return item;
    }
}
