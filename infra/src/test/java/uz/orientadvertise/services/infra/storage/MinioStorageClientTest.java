package uz.orientadvertise.services.infra.storage;

import java.io.ByteArrayInputStream;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.StorageUnavailableException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioStorageClientTest {

    private MinioClient minioClient;
    private MinioClient presignClient;
    private MinioProperties properties;
    private MinioHealthStatus healthStatus;
    private MinioStorageClient storageClient;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        presignClient = mock(MinioClient.class);
        properties = new MinioProperties();
        properties.setPresignedUrlExpiryMinutes(45);
        healthStatus = new MinioHealthStatus();
        healthStatus.markUp();
        storageClient = new MinioStorageClient(minioClient, presignClient, properties, healthStatus);
    }

    @Test
    void upload_delegatesToMinioClient() throws Exception {
        var data = new ByteArrayInputStream("content".getBytes());
        assertDoesNotThrow(() ->
                storageClient.upload("uploads", "file.txt", data, 7, "text/plain"));
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void upload_throwsStorageUnavailable_whenDegraded() {
        healthStatus.markDegraded();
        var data = new ByteArrayInputStream("content".getBytes());
        assertThrows(StorageUnavailableException.class, () ->
                storageClient.upload("uploads", "file.txt", data, 7, "text/plain"));
    }

    @Test
    void generatePresignedUrl_returnsUrl() throws Exception {
        when(presignClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost:9000/uploads/file.txt?token=abc");

        var url = storageClient.generatePresignedUrl("uploads", "file.txt");
        assertEquals("http://localhost:9000/uploads/file.txt?token=abc", url);
    }

    @Test
    void generatePresignedUrl_throwsWhenDegraded() {
        healthStatus.markDegraded();
        assertThrows(StorageUnavailableException.class, () ->
                storageClient.generatePresignedUrl("uploads", "file.txt"));
    }

    @Test
    void generatePresignedUrl_doesNotMarkDegradedOnFailure() throws Exception {
        // Region is preconfigured on the presign client (see MinioConfig), so a presign
        // failure is an arg/programming bug, not a MinIO-availability signal. It must
        // NOT cascade and disable uploads/downloads — keep healthStatus.isAvailable().
        when(presignClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenThrow(new RuntimeException("bad bucket arg"));

        assertThrows(StorageUnavailableException.class, () ->
                storageClient.generatePresignedUrl("uploads", "file.txt"));
        assertTrue(healthStatus.isAvailable());
    }

    @Test
    void delete_delegatesToMinioClient() throws Exception {
        assertDoesNotThrow(() -> storageClient.delete("uploads", "file.txt"));
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void isAvailable_reflectsHealthStatus() {
        healthStatus.markUp();
        assertTrue(storageClient.isAvailable());
        healthStatus.markDegraded();
        assertFalse(storageClient.isAvailable());
    }

    @Test
    void generatePresignedUrl_withCustomExpiry_passesExpiryToMinio() throws Exception {
        when(presignClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost/uploads/file.txt?ttl=2h");

        var url = storageClient.generatePresignedUrl("uploads", "file.txt", 120);
        assertEquals("http://localhost/uploads/file.txt?ttl=2h", url);
    }

    @Test
    void exists_returnsTrueWhenStatSucceeds() throws Exception {
        // statObject returns a non-null result on success — Mockito returns null for unstubbed
        // calls, but we just need it to not throw.
        assertTrue(storageClient.exists("uploads", "file.txt"));
        verify(minioClient).statObject(any(StatObjectArgs.class));
    }

    @Test
    void exists_returnsFalseOnNoSuchKey() throws Exception {
        ErrorResponse err = mock(ErrorResponse.class);
        when(err.code()).thenReturn("NoSuchKey");
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(err);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(ex);

        assertFalse(storageClient.exists("uploads", "missing.mp4"));
    }

    @Test
    void exists_throwsStorageUnavailable_onOtherErrors() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new RuntimeException("network down"));

        assertThrows(StorageUnavailableException.class, () ->
                storageClient.exists("uploads", "anything"));
        assertFalse(healthStatus.isAvailable());
    }

    @Test
    void upload_marksDegradedOnMinioException() throws Exception {
        when(minioClient.putObject(any(PutObjectArgs.class)))
                .thenThrow(new RuntimeException("Network error"));

        var data = new ByteArrayInputStream("content".getBytes());
        assertThrows(StorageUnavailableException.class, () ->
                storageClient.upload("uploads", "file.txt", data, 7, "text/plain"));
        assertFalse(healthStatus.isAvailable());
    }

    @Test
    void download_missingObject_throws404_withoutMarkingDegraded() throws Exception {
        // files-3: a NoSuchKey is a 404, not a storage outage — must NOT flip healthStatus.
        ErrorResponse err = mock(ErrorResponse.class);
        when(err.code()).thenReturn("NoSuchKey");
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(err);
        when(minioClient.getObject(any(io.minio.GetObjectArgs.class))).thenThrow(ex);

        assertThrows(uz.orientadvertise.services.common.exception.ResourceNotFoundException.class, () ->
                storageClient.download("uploads", "missing.mp4"));
        assertTrue(healthStatus.isAvailable());
    }

    @Test
    void download_genuineOutage_marksDegradedAndThrowsStorageUnavailable() throws Exception {
        when(minioClient.getObject(any(io.minio.GetObjectArgs.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertThrows(StorageUnavailableException.class, () ->
                storageClient.download("uploads", "anything.mp4"));
        assertFalse(healthStatus.isAvailable());
    }
}
