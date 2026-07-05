package uz.orientadvertise.services.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.StorageUnavailableException;
import uz.orientadvertise.services.domain.storage.StorageClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileStorageServiceTest {

    private StorageClient storageClient;
    private FileStorageService service;

    @BeforeEach
    void setUp() {
        storageClient = mock(StorageClient.class);
        service = new FileStorageService(storageClient, "content-thumbnails");
    }

    @Test
    void upload_delegatesWithDefaultBucket() {
        var data = new ByteArrayInputStream("data".getBytes());
        service.upload("file.txt", data, 4, "text/plain");
        verify(storageClient).upload(eq("uploads"), eq("file.txt"), any(InputStream.class), eq(4L), eq("text/plain"));
    }

    @Test
    void download_delegatesWithDefaultBucket() {
        var expected = new ByteArrayInputStream("data".getBytes());
        when(storageClient.download("uploads", "file.txt")).thenReturn(expected);

        var result = service.download("file.txt");
        assertEquals(expected, result);
    }

    @Test
    void generatePresignedUrl_delegatesWithDefaultBucket() {
        when(storageClient.generatePresignedUrl("uploads", "file.txt"))
                .thenReturn("http://presigned-url");

        var url = service.generatePresignedUrl("file.txt");
        assertEquals("http://presigned-url", url);
    }

    @Test
    void delete_delegatesWithDefaultBucket() {
        service.delete("file.txt");
        verify(storageClient).delete("uploads", "file.txt");
    }

    @Test
    void isStorageAvailable_reflectsClient() {
        when(storageClient.isAvailable()).thenReturn(true);
        assertTrue(service.isStorageAvailable());

        when(storageClient.isAvailable()).thenReturn(false);
        assertFalse(service.isStorageAvailable());
    }

    @Test
    void presignedProcessedUrl_withExpiry_delegatesToProcessedBucket() {
        when(storageClient.generatePresignedUrl("content-processed", "key/x.mp4", 120))
                .thenReturn("http://signed/x?ttl=2h");

        assertEquals("http://signed/x?ttl=2h", service.presignedProcessedUrl("key/x.mp4", 120));
    }

    @Test
    void presignedThumbnailUrl_delegatesToConfiguredBucket() {
        when(storageClient.generatePresignedUrl("content-thumbnails", "thumb/abc.jpg", 15))
                .thenReturn("http://signed/thumb?ttl=15m");

        assertEquals("http://signed/thumb?ttl=15m",
                service.presignedThumbnailUrl("thumb/abc.jpg", 15));
    }

    @Test
    void presignedThumbnailUrl_honorsCustomBucketName() {
        var custom = new FileStorageService(storageClient, "custom-thumbs");
        when(storageClient.generatePresignedUrl("custom-thumbs", "k.jpg", 15))
                .thenReturn("http://signed/custom");

        assertEquals("http://signed/custom", custom.presignedThumbnailUrl("k.jpg", 15));
    }

    @Test
    void processedObjectExists_delegatesToProcessedBucket() {
        when(storageClient.exists("content-processed", "key/missing.mp4")).thenReturn(false);
        when(storageClient.exists("content-processed", "key/found.mp4")).thenReturn(true);

        assertFalse(service.processedObjectExists("key/missing.mp4"));
        assertTrue(service.processedObjectExists("key/found.mp4"));
    }

    @Test
    void upload_propagatesStorageUnavailable() {
        doThrow(new StorageUnavailableException("unavailable"))
                .when(storageClient).upload(anyString(), anyString(), any(), anyLong(), anyString());

        var data = new ByteArrayInputStream("data".getBytes());
        assertThrows(StorageUnavailableException.class, () ->
                service.upload("file.txt", data, 4, "text/plain"));
    }
}
