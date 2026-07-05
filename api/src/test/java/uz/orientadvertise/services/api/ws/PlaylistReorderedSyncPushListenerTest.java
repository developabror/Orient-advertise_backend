package uz.orientadvertise.services.api.ws;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.content.SyncDispatcher;
import uz.orientadvertise.services.service.ContentAssignmentService;
import uz.orientadvertise.services.service.PlaylistReorderedEvent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirror of {@link AssignmentConfirmedSyncPushListenerTest} for the playlist-mutation
 * push path. AFTER_COMMIT gating is Spring's contract, not re-tested here — the
 * boundary we own is "given an event, do we resolve the right devices and call the
 * dispatcher exactly once".
 */
class PlaylistReorderedSyncPushListenerTest {

    private SyncDispatcher syncDispatcher;
    private ContentAssignmentService assignmentService;
    private PlaylistReorderedSyncPushListener listener;

    @BeforeEach
    void setUp() {
        syncDispatcher = mock(SyncDispatcher.class);
        when(syncDispatcher.dispatchSyncToDevices(any(), anyString()))
                .thenReturn(new SyncDispatcher.DispatchResult(0, 0, 0, 0, 0));
        assignmentService = mock(ContentAssignmentService.class);
        listener = new PlaylistReorderedSyncPushListener(syncDispatcher, assignmentService);
    }

    @Test
    void onPlaylistReordered_resolvedDevices_dispatchesOnce_withPlaylistReorderedReason() {
        when(assignmentService.resolveDeviceIdsForActivePlaylist(eq(7L), any(Instant.class)))
                .thenReturn(List.of(10L, 20L));

        listener.onPlaylistReordered(new PlaylistReorderedEvent(7L));

        verify(syncDispatcher, times(1))
                .dispatchSyncToDevices(eq(List.of(10L, 20L)), eq("playlist-reordered"));
    }

    @Test
    void onPlaylistReordered_noActiveBinding_neverCallsDispatcher() {
        // No active assignment references this playlist → service returns empty.
        when(assignmentService.resolveDeviceIdsForActivePlaylist(anyLong(), any(Instant.class)))
                .thenReturn(List.of());

        listener.onPlaylistReordered(new PlaylistReorderedEvent(8L));

        verify(syncDispatcher, never()).dispatchSyncToDevices(any(), anyString());
    }

    @Test
    void onPlaylistReordered_nullResolvedList_neverCallsDispatcher() {
        // Defensive: a null return (shouldn't happen, but cheap to guard) must not
        // reach the dispatcher with a null collection.
        when(assignmentService.resolveDeviceIdsForActivePlaylist(anyLong(), any(Instant.class)))
                .thenReturn(null);

        listener.onPlaylistReordered(new PlaylistReorderedEvent(9L));

        verify(syncDispatcher, never()).dispatchSyncToDevices(any(), anyString());
    }

    @Test
    void onPlaylistReordered_serviceThrows_neverCallsDispatcher() {
        // A transient DB hiccup in the resolver should not silently push a partial
        // device set. The listener lets the exception propagate (logged by Spring's
        // TransactionalEventListener machinery).
        when(assignmentService.resolveDeviceIdsForActivePlaylist(anyLong(), any(Instant.class)))
                .thenThrow(new RuntimeException("DB timeout"));

        assertThrows(RuntimeException.class,
                () -> listener.onPlaylistReordered(new PlaylistReorderedEvent(10L)));

        verify(syncDispatcher, never()).dispatchSyncToDevices(any(), anyString());
    }
}
