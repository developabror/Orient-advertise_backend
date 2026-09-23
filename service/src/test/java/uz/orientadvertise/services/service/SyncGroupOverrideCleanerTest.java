package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.SyncGroupPlaybackOverrideRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VG-10: retiring a stale group-jump override is the playlist edit's job — once — not something
 * every syncing member of the group races to do.
 */
class SyncGroupOverrideCleanerTest {

    private ContentAssignmentRepository assignmentRepository;
    private SyncGroupPlaybackOverrideRepository overrideRepository;
    private SyncGroupOverrideCleaner cleaner;

    @BeforeEach
    void setUp() {
        assignmentRepository = mock(ContentAssignmentRepository.class);
        overrideRepository = mock(SyncGroupPlaybackOverrideRepository.class);
        cleaner = new SyncGroupOverrideCleaner(assignmentRepository, overrideRepository);
    }

    @Test
    void retiresOverridesOfEveryAssignmentActiveOnTheEditedPlaylist() {
        var a = mock(ContentAssignment.class);
        when(a.getId()).thenReturn(10L);
        var b = mock(ContentAssignment.class);
        when(b.getId()).thenReturn(11L);
        when(assignmentRepository.findActiveByPlaylistId(eq(5L), any())).thenReturn(List.of(a, b));
        when(overrideRepository.deleteByAssignmentIdIn(List.of(10L, 11L))).thenReturn(2);

        cleaner.onPlaylistReordered(new PlaylistReorderedEvent(5L));

        // One bulk statement, not one derived delete per member: a row somebody else already
        // removed affects 0 rows here instead of raising an optimistic-lock failure (a 500 plus a
        // Telegram alert for a device whose sync was otherwise fine).
        verify(overrideRepository).deleteByAssignmentIdIn(List.of(10L, 11L));
    }

    @Test
    void doesNothingWhenNoAssignmentUsesThePlaylist() {
        when(assignmentRepository.findActiveByPlaylistId(eq(6L), any())).thenReturn(List.of());

        cleaner.onPlaylistReordered(new PlaylistReorderedEvent(6L));

        verify(overrideRepository, never()).deleteByAssignmentIdIn(any());
        verify(overrideRepository, never()).deleteBySyncGroupId(anyLong());
    }

    @Test
    void resolvesAssignmentsAtListenerTime() {
        // The event carries only the playlist id on purpose: an assignment confirmed between the
        // edit and this call is included, exactly like the sibling push listener.
        when(assignmentRepository.findActiveByPlaylistId(eq(7L), any())).thenReturn(List.of());

        cleaner.onPlaylistReordered(new PlaylistReorderedEvent(7L));

        verify(assignmentRepository).findActiveByPlaylistId(eq(7L), any(Instant.class));
    }
}
