package uz.orientadvertise.services.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Service-layer companion to {@code ContentVersionHasherTest}. Hashing itself is
 * already covered at the util layer; this test pins the load-bearing wiring:
 * {@code ContentVersionService.computeForAssignment} feeds items through
 * {@link PlaylistItemRepository#findByPlaylistIdOrderByPositionAsc} (NOT a sort
 * inside the service), so reordering the items at the repo layer must propagate
 * to a different hash. Without this, a future refactor that started sorting by
 * file id or content-file name would silently make reorders look identical to
 * device sync — devices would never re-sync after an operator reorder.
 */
class ContentVersionServiceTest {

    private ContentAssignmentService assignmentService;
    private PlaylistItemRepository playlistItemRepository;
    private ContentVersionService versionService;

    @BeforeEach
    void setUp() {
        assignmentService = mock(ContentAssignmentService.class);
        playlistItemRepository = mock(PlaylistItemRepository.class);
        versionService = new ContentVersionService(assignmentService, playlistItemRepository);
    }

    @Test
    void computeForAssignment_orderChange_producesDifferentHash_restoresOnRevert() {
        ContentAssignment assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(42L);
        when(assignment.getVersionNumber()).thenReturn(1);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(100L);
        when(assignment.getPlaylist()).thenReturn(playlist);

        // Two READY items with distinct content files. The hash is built from
        // (contentFileId, processedStorageKey) pairs in the order the repo returns.
        PlaylistItem itemA = itemOn(0, fileWithKey(10L, "key/a.mp4"));
        PlaylistItem itemB = itemOn(1, fileWithKey(11L, "key/b.mp4"));

        // Original order [A, B] → h0
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(eq(100L)))
                .thenReturn(List.of(itemA, itemB));
        String h0 = versionService.computeForAssignment(assignment);

        // Reversed order [B, A] → h1; must differ.
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(eq(100L)))
                .thenReturn(List.of(itemB, itemA));
        String h1 = versionService.computeForAssignment(assignment);

        // Restored order [A, B] → h2; must equal h0.
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(eq(100L)))
                .thenReturn(List.of(itemA, itemB));
        String h2 = versionService.computeForAssignment(assignment);

        assertNotNull(h0);
        assertNotNull(h1);
        assertNotNull(h2);
        assertNotEquals(h0, h1, "Reordering items must change the version hash");
        assertEquals(h0, h2, "Restoring the original order must return the original hash");
    }

    @Test
    void computeForAssignment_durationEdit_producesDifferentHash_restoresOnRevert() {
        // Same file, same order — only the per-item dwell duration changes. Folding the
        // effective duration into the hash is what keeps the /sync schedule stable per
        // contentVersion; without it a dwell edit would ship mismatched slot timings silently.
        ContentAssignment assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(9L);
        when(assignment.getVersionNumber()).thenReturn(1);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(90L);
        when(assignment.getPlaylist()).thenReturn(playlist);

        // Build the stubbing helpers BEFORE the thenReturn(...) — nesting a helper that itself calls
        // when(...) inside thenReturn(List.of(...)) trips Mockito's UnfinishedStubbingException.
        PlaylistItem dwell30 = itemOn(0, fileWithKey(10L, "key/a.mp4"), 30);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(eq(90L)))
                .thenReturn(List.of(dwell30));
        String h0 = versionService.computeForAssignment(assignment);

        PlaylistItem dwell45 = itemOn(0, fileWithKey(10L, "key/a.mp4"), 45);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(eq(90L)))
                .thenReturn(List.of(dwell45));
        String hEdited = versionService.computeForAssignment(assignment);

        PlaylistItem dwell30again = itemOn(0, fileWithKey(10L, "key/a.mp4"), 30);
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(eq(90L)))
                .thenReturn(List.of(dwell30again));
        String hRestored = versionService.computeForAssignment(assignment);

        assertNotEquals(h0, hEdited, "A dwell-time edit must change the version hash");
        assertEquals(h0, hRestored, "Restoring the duration must return the original hash");
    }

    @Test
    void computeForAssignment_sameOrderTwice_returnsSameHash() {
        // Determinism smoke test — guards against a stray timestamp / random salt leaking
        // into the hash inputs in a future refactor.
        ContentAssignment assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(7L);
        when(assignment.getVersionNumber()).thenReturn(3);
        Playlist playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(70L);
        when(assignment.getPlaylist()).thenReturn(playlist);

        PlaylistItem only = itemOn(0, fileWithKey(700L, "key/only.mp4"));
        when(playlistItemRepository.findByPlaylistIdOrderByPositionAsc(eq(70L)))
                .thenReturn(List.of(only));

        assertEquals(
                versionService.computeForAssignment(assignment),
                versionService.computeForAssignment(assignment));
    }

    private static ContentFile fileWithKey(Long id, String processedKey) {
        ContentFile f = mock(ContentFile.class);
        when(f.getId()).thenReturn(id);
        when(f.getProcessedStorageKey()).thenReturn(processedKey);
        return f;
    }

    private static PlaylistItem itemOn(int position, ContentFile file) {
        PlaylistItem item = mock(PlaylistItem.class);
        when(item.getPosition()).thenReturn(position);
        when(item.getContentFile()).thenReturn(file);
        return item;
    }

    private static PlaylistItem itemOn(int position, ContentFile file, Integer durationSeconds) {
        PlaylistItem item = itemOn(position, file);
        when(item.getDurationSeconds()).thenReturn(durationSeconds);
        return item;
    }
}
