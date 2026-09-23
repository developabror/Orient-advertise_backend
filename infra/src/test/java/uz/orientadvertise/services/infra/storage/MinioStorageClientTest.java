package uz.orientadvertise.services.infra.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.ServerException;
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
    private MinioClient metadataClient;
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
        // exists() runs on the SHORT-timeout metadata client (VG-07); the same mock stands in for
        // both here, and a dedicated test below pins which client each call actually uses.
        metadataClient = minioClient;
        storageClient = new MinioStorageClient(minioClient, presignClient, metadataClient, properties, healthStatus);
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
        healthStatus.markDegraded("upload: ConnectException");
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
    void generatePresignedUrl_succeedsWhileDegraded_becauseSigningIsLocalCrypto() throws Exception {
        // v1.0.144 inverts the old behaviour deliberately. getPresignedObjectUrl is an HMAC over
        // strings with the region preconfigured — it never touches the network. Gating it on the
        // health flag is what let one MinIO blip hand every device a SHORT playlist: /sync could
        // describe the files but not mint URLs for them, so it dropped them and answered 200.
        healthStatus.markDegraded("upload: ConnectException");
        when(presignClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost:9000/uploads/file.txt?token=abc");

        assertEquals("http://localhost:9000/uploads/file.txt?token=abc",
                storageClient.generatePresignedUrl("uploads", "file.txt"));
        assertFalse(healthStatus.isAvailable(), "presigning must not heal the flag either");
    }

    @Test
    void generatePresignedUrl_withCustomExpiry_alsoSucceedsWhileDegraded() throws Exception {
        healthStatus.markDegraded("object stat: SocketTimeoutException");
        when(presignClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost/uploads/file.txt?ttl=2h");

        assertEquals("http://localhost/uploads/file.txt?ttl=2h",
                storageClient.generatePresignedUrl("uploads", "file.txt", 120));
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
        healthStatus.markDegraded("upload: ConnectException");
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
    void exists_throwsStorageUnavailable_andDegrades_onConnectionFailure() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new SocketTimeoutException("read timed out"));

        assertThrows(StorageUnavailableException.class, () ->
                storageClient.exists("uploads", "anything"));
        assertFalse(healthStatus.isAvailable());
    }

    @Test
    void upload_marksDegradedOnConnectionFailure() throws Exception {
        when(minioClient.putObject(any(PutObjectArgs.class)))
                .thenThrow(new ConnectException("Connection refused"));

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
                .thenThrow(new ConnectException("connection refused"));

        assertThrows(StorageUnavailableException.class, () ->
                storageClient.download("uploads", "anything.mp4"));
        assertFalse(healthStatus.isAvailable());
    }

    // ---------------------------------------------------------------------------------------
    // v1.0.144 — which failures are evidence that MinIO is DOWN, and which are evidence that one
    // REQUEST was wrong. Every call site used to answer "down" by catching Exception, so a single
    // bad object, a denied ACL or a full quota latched the whole process into 503 until a restart.
    // ---------------------------------------------------------------------------------------

    /**
     * Build the S3 error mock into a LOCAL first, then stub with it. Passing {@code s3Error(...)}
     * straight into {@code thenThrow(...)} runs this helper's own {@code when(...)} calls while
     * the outer stubbing is still open → {@code UnfinishedStubbingException}, reported against the
     * outer line. See tasks/lessons.md.
     */
    private static ErrorResponseException s3Error(String code) {
        return s3Error(code, 404);
    }

    private static ErrorResponseException s3Error(String code, int httpStatus) {
        ErrorResponse err = mock(ErrorResponse.class);
        when(err.code()).thenReturn(code);
        ErrorResponseException ex = mock(ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(err);
        when(ex.response()).thenReturn(new okhttp3.Response.Builder()
                .request(new okhttp3.Request.Builder().url("http://minio:9000/b/k").build())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(httpStatus)
                .message("status " + httpStatus)
                .build());
        return ex;
    }

    @Test
    void upload_quotaFull_doesNotDegrade_butStillFailsTheCaller() throws Exception {
        // XMinioStorageFull: MinIO is up, talking, and out of disk. Degrading here means the
        // operator who frees space still cannot upload until someone restarts the application.
        ErrorResponseException quotaFull = s3Error("XMinioStorageFull", 507);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(quotaFull);

        var data = new ByteArrayInputStream("content".getBytes());
        assertThrows(StorageUnavailableException.class, () ->
                storageClient.upload("uploads", "file.txt", data, 7, "text/plain"));
        assertTrue(healthStatus.isAvailable(), "a full bucket is not an unreachable MinIO");
    }

    @Test
    void upload_accessDenied_doesNotDegrade_butStillFailsTheCaller() throws Exception {
        ErrorResponseException denied = s3Error("AccessDenied", 403);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(denied);

        var data = new ByteArrayInputStream("content".getBytes());
        assertThrows(StorageUnavailableException.class, () ->
                storageClient.upload("uploads", "file.txt", data, 7, "text/plain"));
        assertTrue(healthStatus.isAvailable());
    }

    @Test
    void exists_accessDenied_doesNotDegrade_butStillFailsTheCaller() throws Exception {
        // The /sync path: one unreadable key must not 503 every other device's playlist.
        ErrorResponseException denied = s3Error("AccessDenied", 403);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(denied);

        assertThrows(StorageUnavailableException.class, () ->
                storageClient.exists("content-processed", "processed/x.mp4"));
        assertTrue(healthStatus.isAvailable());
    }

    @Test
    void exists_noSuchBucket_returnsFalse_withoutDegrading() throws Exception {
        ErrorResponseException noBucket = s3Error("NoSuchBucket");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(noBucket);

        assertFalse(storageClient.exists("content-processed", "processed/x.mp4"));
        assertTrue(healthStatus.isAvailable());
    }

    @Test
    void download_accessDenied_doesNotDegrade_andIsNotA404() throws Exception {
        ErrorResponseException denied = s3Error("AccessDenied", 403);
        when(minioClient.getObject(any(io.minio.GetObjectArgs.class))).thenThrow(denied);

        assertThrows(StorageUnavailableException.class, () ->
                storageClient.download("uploads", "file.txt"));
        assertTrue(healthStatus.isAvailable());
    }

    @Test
    void delete_noSuchKey_doesNotDegrade() throws Exception {
        ErrorResponseException noKey = s3Error("NoSuchKey");
        org.mockito.Mockito.doThrow(noKey).when(minioClient).removeObject(any(RemoveObjectArgs.class));

        assertThrows(StorageUnavailableException.class, () ->
                storageClient.delete("uploads", "gone.txt"));
        assertTrue(healthStatus.isAvailable());
    }

    @Test
    void upload_serverException_degrades_becauseA5xxMeansMinioCannotServe() throws Exception {
        when(minioClient.putObject(any(PutObjectArgs.class)))
                .thenThrow(new ServerException("Internal Server Error", 500, null));

        var data = new ByteArrayInputStream("content".getBytes());
        assertThrows(StorageUnavailableException.class, () ->
                storageClient.upload("uploads", "file.txt", data, 7, "text/plain"));
        assertFalse(healthStatus.isAvailable());
        assertTrue(healthStatus.getReason().contains("ServerException"), healthStatus.getReason());
    }

    @Test
    void exists_ioException_degrades_withAnOperationScopedReason() throws Exception {
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new IOException("unexpected end of stream"));

        assertThrows(StorageUnavailableException.class, () ->
                storageClient.exists("content-processed", "processed/x.mp4"));
        assertFalse(healthStatus.isAvailable());
        // The reason is published on the UNAUTHENTICATED /api/health — operation + exception
        // TYPE only, never the message (endpoints, hostnames) or the object key (filenames).
        assertEquals("object stat: IOException", healthStatus.getReason());
        assertFalse(healthStatus.getReason().contains("unexpected end of stream"));
    }

    @Test
    void delete_ioException_degrades() throws Exception {
        org.mockito.Mockito.doThrow(new ConnectException("Connection refused"))
                .when(minioClient).removeObject(any(RemoveObjectArgs.class));

        assertThrows(StorageUnavailableException.class, () ->
                storageClient.delete("uploads", "file.txt"));
        assertFalse(healthStatus.isAvailable());
    }

    @Test
    void argumentBug_staysPerRequest() throws Exception {
        // Not IOException, not a 5xx, not an S3 answer — a caller bug about one call.
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new IllegalArgumentException("bucket name cannot be empty"));

        assertThrows(StorageUnavailableException.class, () ->
                storageClient.exists("", "x"));
        assertTrue(healthStatus.isAvailable());
    }

    @Test
    void ioExceptionWrappedInARuntimeException_degrades() throws Exception {
        // minio-java's S3Base.throwEncapsulatedException wraps anything outside its nine declared
        // types in a bare RuntimeException, so a real outage can arrive with the IOException one
        // level down. Type-only classification silently misses it — /api/health would say UP.
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new RuntimeException(new ConnectException("Connection refused")));

        assertThrows(StorageUnavailableException.class, () ->
                storageClient.exists("content-processed", "processed/x.mp4"));
        assertFalse(healthStatus.isAvailable());
    }

    @Test
    void s3ErrorWith5xxStatus_degrades_becauseMinioSaidItCouldNotServe() throws Exception {
        // "MinIO up but broken": a 5xx WITH an S3 body is an ErrorResponseException, never a
        // ServerException. Treating it as per-request leaves an outage entirely unreported.
        ErrorResponseException internal = s3Error("InternalError", 500);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(internal);

        var data = new ByteArrayInputStream("content".getBytes());
        assertThrows(StorageUnavailableException.class, () ->
                storageClient.upload("uploads", "file.txt", data, 7, "text/plain"));
        assertFalse(healthStatus.isAvailable());
    }

    // ---------------------------------------------------------------------------------------
    // What the CALLER is told. Since v1.0.144 a storage failure reaches TV boxes through /sync
    // and /playlist, so the 503 body must not carry MinIO's own message — which names the
    // endpoint, the bucket and the object key.
    // ---------------------------------------------------------------------------------------

    @Test
    void the503Message_neverEchoesMinioInternals() throws Exception {
        String leaky = "S3 operation failed; endpoint http://minio:9000 bucket content-processed "
                + "key processed/2f1a_customer-contract.mp4 accessKey AKIAEXAMPLE";
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new IOException(leaky));

        var e = assertThrows(StorageUnavailableException.class, () ->
                storageClient.exists("content-processed", "processed/2f1a_customer-contract.mp4"));

        assertEquals(MinioStorageClient.CLIENT_MESSAGE, e.getMessage());
        assertFalse(e.getMessage().contains("minio:9000"), e.getMessage());
        assertFalse(e.getMessage().contains("content-processed"), e.getMessage());
        assertFalse(e.getMessage().contains("customer-contract"), e.getMessage());
        assertFalse(e.getMessage().contains("AKIAEXAMPLE"), e.getMessage());
    }

    @Test
    void everyStorageFailurePath_returnsTheSameFixedClientMessage() throws Exception {
        // upload (connection-level), delete (S3 answer) and the degraded short-circuit must all
        // read the same to a device — there is nothing useful a TV box can do with the difference.
        when(minioClient.putObject(any(PutObjectArgs.class)))
                .thenThrow(new ConnectException("Failed to connect to minio/172.18.0.3:9000"));
        var data = new ByteArrayInputStream("content".getBytes());
        var fromUpload = assertThrows(StorageUnavailableException.class, () ->
                storageClient.upload("uploads", "f.txt", data, 7, "text/plain"));

        ErrorResponseException denied = s3Error("AccessDenied", 403);
        org.mockito.Mockito.doThrow(denied).when(minioClient).removeObject(any(RemoveObjectArgs.class));
        var fromDelete = assertThrows(StorageUnavailableException.class, () ->
                storageClient.delete("uploads", "f.txt"));

        var fromShortCircuit = assertThrows(StorageUnavailableException.class, () ->
                storageClient.download("uploads", "f.txt"));   // storage is degraded by now

        assertEquals(MinioStorageClient.CLIENT_MESSAGE, fromUpload.getMessage());
        assertEquals(MinioStorageClient.CLIENT_MESSAGE, fromDelete.getMessage());
        assertEquals(MinioStorageClient.CLIENT_MESSAGE, fromShortCircuit.getMessage());
    }

    @Test
    void presignFailureMessage_alsoCarriesNoInternals() throws Exception {
        when(presignClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenThrow(new IllegalArgumentException("bucket http://minio:9000/secret-bucket invalid"));

        var e = assertThrows(StorageUnavailableException.class, () ->
                storageClient.generatePresignedUrl("secret-bucket", "k.mp4"));

        assertEquals(MinioStorageClient.CLIENT_MESSAGE, e.getMessage());
        assertTrue(healthStatus.isAvailable(), "a presign bug is still not an availability signal");
    }
}
