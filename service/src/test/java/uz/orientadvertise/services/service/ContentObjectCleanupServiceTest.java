package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.common.exception.StorageUnavailableException;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** VG-08: deleted content must stop costing disk, without ever deleting bytes still in use. */
class ContentObjectCleanupServiceTest {

    private static final Duration GRACE = Duration.ofDays(7);

    private ContentFileRepository repository;
    private FileStorageService storage;
    private ContentObjectCleanupService service;

    @BeforeEach
    void setUp() {
        repository = mock(ContentFileRepository.class);
        storage = mock(FileStorageService.class);
        when(storage.isStorageAvailable()).thenReturn(true);
        service = new ContentObjectCleanupService(repository, storage, GRACE, 200);
    }

    @Test
    void deletesBothObjects_thenForgetsTheKeys() {
        var file = deleted(1L, "processed/a.mp4", "thumbs/a.jpg");   // build mocks before stubbing with them
        when(repository.findDeletedWithStorageObjects(any(), any())).thenReturn(List.of(file));

        service.reclaimDeletedContentObjects();

        var order = org.mockito.Mockito.inOrder(storage, repository);
        order.verify(storage).deleteProcessed("processed/a.mp4");
        order.verify(storage).deleteThumbnail("thumbs/a.jpg");
        // Order matters: clearing first would lose the only pointer to bytes still in the bucket.
        order.verify(repository).clearStorageKeysOfDeleted(eq(1L), any(Instant.class));
    }

    @Test
    void onlyLooksBackBeyondTheGracePeriod() {
        when(repository.findDeletedWithStorageObjects(any(), any())).thenReturn(List.of());

        service.reclaimDeletedContentObjects();

        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findDeletedWithStorageObjects(cutoff.capture(), any());
        assertTrue(cutoff.getValue().isBefore(Instant.now().minus(GRACE).plusSeconds(5)),
                "a just-deleted file must keep its bytes for the whole grace window");
    }

    @Test
    void skipsEntirelyWhenStorageIsDown() {
        when(storage.isStorageAvailable()).thenReturn(false);

        service.reclaimDeletedContentObjects();

        verifyNoInteractions(repository);
    }

    @Test
    void aFailedDeleteKeepsTheKeys_soTheFileIsRetried() {
        var file = deleted(2L, "processed/b.mp4", null);
        when(repository.findDeletedWithStorageObjects(any(), any())).thenReturn(List.of(file));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(storage).deleteProcessed("processed/b.mp4");

        service.reclaimDeletedContentObjects();

        // Forgetting the key here would strand the bytes forever — nothing would ever point at them.
        verify(repository, never()).clearStorageKeysOfDeleted(anyLong(), any());
    }

    @Test
    void oneBadFileDoesNotStrandTheRestOfTheBatch() {
        var bad = deleted(3L, "processed/c.mp4", null);
        var good = deleted(4L, "processed/d.mp4", null);
        when(repository.findDeletedWithStorageObjects(any(), any())).thenReturn(List.of(bad, good));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(storage).deleteProcessed("processed/c.mp4");

        service.reclaimDeletedContentObjects();

        verify(storage).deleteProcessed("processed/d.mp4");
        verify(repository).clearStorageKeysOfDeleted(eq(4L), any());
    }

    @Test
    void stopsEarlyWhenStorageDisappearsMidSweep() {
        var first = deleted(5L, "processed/e.mp4", null);
        var second = deleted(6L, "processed/f.mp4", null);
        when(repository.findDeletedWithStorageObjects(any(), any())).thenReturn(List.of(first, second));
        org.mockito.Mockito.doThrow(new StorageUnavailableException("down"))
                .when(storage).deleteProcessed("processed/e.mp4");

        service.reclaimDeletedContentObjects();

        // No point walking the rest of the batch: every call would fail the same way and log.
        verify(storage, never()).deleteProcessed("processed/f.mp4");
    }

    @Test
    void aFileWithOnlyAProcessedObject_doesNotCallTheThumbnailDelete() {
        var file = deleted(7L, "processed/g.mp4", null);
        when(repository.findDeletedWithStorageObjects(any(), any())).thenReturn(List.of(file));

        service.reclaimDeletedContentObjects();

        verify(storage, never()).deleteThumbnail(anyString());
        verify(repository).clearStorageKeysOfDeleted(eq(7L), any());
    }

    private static ContentFile deleted(Long id, String processedKey, String thumbnailKey) {
        ContentFile f = mock(ContentFile.class);
        when(f.getId()).thenReturn(id);
        when(f.getProcessedStorageKey()).thenReturn(processedKey);
        when(f.getThumbnailStorageKey()).thenReturn(thumbnailKey);
        return f;
    }
}
