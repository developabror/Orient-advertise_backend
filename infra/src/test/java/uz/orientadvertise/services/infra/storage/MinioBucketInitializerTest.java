package uz.orientadvertise.services.infra.storage;

import java.util.List;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioBucketInitializerTest {

    private MinioClient minioClient;
    private MinioProperties properties;
    private MinioHealthStatus healthStatus;
    private MinioBucketInitializer initializer;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        properties = new MinioProperties();
        properties.setBuckets(List.of("uploads", "documents"));
        healthStatus = new MinioHealthStatus();
        initializer = new MinioBucketInitializer(minioClient, properties, healthStatus);
    }

    @Test
    void run_createsMissingBuckets_marksUp() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        initializer.run(null);

        verify(minioClient, times(2)).makeBucket(any(MakeBucketArgs.class));
        assertTrue(healthStatus.isAvailable());
    }

    @Test
    void run_skipsExistingBuckets() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        initializer.run(null);

        verify(minioClient, never()).makeBucket(any(MakeBucketArgs.class));
        assertTrue(healthStatus.isAvailable());
    }

    @Test
    void run_minioUnavailable_marksDegraded_doesNotThrow() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        assertDoesNotThrow(() -> initializer.run(null));
        assertFalse(healthStatus.isAvailable());
    }

    @Test
    void run_minioUnavailable_appStillStarts() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new java.net.ConnectException("Connection refused"));

        assertDoesNotThrow(() -> initializer.run(null));
        assertEquals(MinioHealthStatus.State.DEGRADED, healthStatus.getState());
    }

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
