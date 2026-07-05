package uz.orientadvertise.services.infra.storage;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.StorageUnavailableException;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.storage.StorageClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContentMetadataReconcilerTest {

    private static final String PROCESSED_BUCKET = "content-processed";

    private ContentFileRepository repo;
    private StorageClient storage;

    @BeforeEach
    void setUp() {
        repo = mock(ContentFileRepository.class);
        storage = mock(StorageClient.class);
    }

    private ContentMetadataReconciler reconciler(boolean enabled) {
        return new ContentMetadataReconciler(repo, storage, enabled);
    }

    private static String sha256Hex(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private ContentFile staleFile(Long id, String key, long storedSize) {
        var f = mock(ContentFile.class);
        when(f.getId()).thenReturn(id);
        when(f.getChecksum()).thenReturn(null);
        when(f.getProcessedStorageKey()).thenReturn(key);
        when(f.getSizeBytes()).thenReturn(storedSize);
        when(repo.findById(id)).thenReturn(Optional.of(f));
        return f;
    }

    @Test
    void disabled_doesNothing() {
        reconciler(false).reconcileOnStartup();
        verifyNoInteractions(repo);
        verifyNoInteractions(storage);
    }

    @Test
    void storageUnavailable_skips_withoutQuerying() {
        when(storage.isAvailable()).thenReturn(false);

        reconciler(true).reconcileOnStartup();

        verify(storage).isAvailable();
        verifyNoInteractions(repo);
    }

    @Test
    void noStaleFiles_doesNothing() {
        when(storage.isAvailable()).thenReturn(true);
        when(repo.findByStatusAndChecksumIsNullAndProcessedStorageKeyIsNotNullAndDeletedAtIsNull(
                ContentFile.Status.READY)).thenReturn(List.of());

        reconciler(true).reconcileOnStartup();

        verify(repo, never()).save(any());
        verify(storage, never()).download(any(), any());
    }

    @Test
    void reconcilesSizeAndChecksum_fromStoredObject() throws Exception {
        when(storage.isAvailable()).thenReturn(true);
        var f = staleFile(7L, "processed/abc.mp4", 999L); // stored size is wrong (pre-fix original)
        when(repo.findByStatusAndChecksumIsNullAndProcessedStorageKeyIsNotNullAndDeletedAtIsNull(
                ContentFile.Status.READY)).thenReturn(List.of(f));
        when(storage.download(eq(PROCESSED_BUCKET), eq("processed/abc.mp4")))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes())); // 11 bytes

        reconciler(true).reconcileOnStartup();

        verify(f).setSizeBytes(11L);
        verify(f).setChecksum(sha256Hex("video bytes"));
        verify(repo).save(f);
    }

    @Test
    void storageErrorOnOneFile_continuesWithTheRest() {
        when(storage.isAvailable()).thenReturn(true);
        var f1 = staleFile(1L, "processed/1.mp4", 11L);
        var f2 = staleFile(2L, "processed/2.mp4", 11L);
        when(repo.findByStatusAndChecksumIsNullAndProcessedStorageKeyIsNotNullAndDeletedAtIsNull(
                ContentFile.Status.READY)).thenReturn(List.of(f1, f2));
        when(storage.download(eq(PROCESSED_BUCKET), eq("processed/1.mp4")))
                .thenThrow(new StorageUnavailableException("boom"));
        when(storage.download(eq(PROCESSED_BUCKET), eq("processed/2.mp4")))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));

        reconciler(true).reconcileOnStartup();

        verify(repo, never()).save(f1);   // failed file is skipped, not saved
        verify(f2).setChecksum(anyString());
        verify(repo).save(f2);            // healthy file still reconciled
    }

    @Test
    void doesNotRewriteSize_whenAlreadyCorrect() throws Exception {
        // Guards the `if (actual != stored)` conditional: when the stored size already matches the
        // object, only the checksum is backfilled — setSizeBytes must NOT be called.
        when(storage.isAvailable()).thenReturn(true);
        var f = staleFile(8L, "processed/ok.mp4", 11L); // stored size already == 11-byte object
        when(repo.findByStatusAndChecksumIsNullAndProcessedStorageKeyIsNotNullAndDeletedAtIsNull(
                ContentFile.Status.READY)).thenReturn(List.of(f));
        when(storage.download(eq(PROCESSED_BUCKET), eq("processed/ok.mp4")))
                .thenReturn(new ByteArrayInputStream("video bytes".getBytes()));

        reconciler(true).reconcileOnStartup();

        verify(f, never()).setSizeBytes(anyLong());
        verify(f).setChecksum(sha256Hex("video bytes"));
        verify(repo).save(f);
    }

    @Test
    void skipsFileThatGainedChecksumConcurrently() {
        when(storage.isAvailable()).thenReturn(true);
        var f = mock(ContentFile.class);
        when(f.getId()).thenReturn(5L);
        when(repo.findByStatusAndChecksumIsNullAndProcessedStorageKeyIsNotNullAndDeletedAtIsNull(
                ContentFile.Status.READY)).thenReturn(List.of(f));
        // Re-fetch shows it already has a checksum (a concurrent transcode/reconcile won the race).
        // processedStorageKey is non-null so the ONLY reason to skip is the checksum re-check —
        // this isolates that precondition (a null key would otherwise mask it).
        when(repo.findById(5L)).thenReturn(Optional.of(f));
        when(f.getProcessedStorageKey()).thenReturn("processed/5.mp4");
        when(f.getChecksum()).thenReturn("already-set");

        reconciler(true).reconcileOnStartup();

        verify(f).getChecksum();
        verify(storage, never()).download(any(), any());
        verify(repo, never()).save(any());
    }
}
