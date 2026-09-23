package uz.orientadvertise.services.infra.storage;

import java.io.IOException;
import java.net.ConnectException;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The missing second writer of {@link MinioHealthStatus}.
 *
 * <p>Before this component the only code that could set storage back to UP ran once, at startup.
 * Every test here is a regression guard for "an outage that only a restart can clear".
 */
class MinioHealthProbeTest {

    /** The probe-scoped, short-timeout client — see MinioConfig#minioProbeClient. */
    private MinioClient minioClient;
    private MinioProperties properties;
    private MinioHealthStatus healthStatus;
    private MinioHealthProbe probe;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        properties = new MinioProperties();
        properties.setRawBucket("content-raw");
        healthStatus = new MinioHealthStatus();
        probe = new MinioHealthProbe(minioClient, properties, healthStatus);
    }

    /**
     * Enter DEGRADED from UP. A fresh {@link MinioHealthStatus} is ALREADY degraded ("not probed
     * yet"), and re-marking a state deliberately keeps the original reason — so a test that wants
     * a specific reason has to transition through UP first.
     */
    private void degrade(String reason) {
        healthStatus.markUp();
        healthStatus.markDegraded(reason);
    }

    @Test
    void degraded_andProbeSucceeds_marksUp() throws Exception {
        degrade("upload: ConnectException");
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        probe.recheck();

        assertTrue(healthStatus.isAvailable(),
                "a reachable MinIO must clear the latch without a restart");
        assertEquals("available", healthStatus.getReason());
    }

    @Test
    void degraded_probesTheConfiguredRawBucket() throws Exception {
        properties.setRawBucket("some-other-bucket");
        degrade("delete: IOException");
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        probe.recheck();

        var captor = ArgumentCaptor.forClass(BucketExistsArgs.class);
        verify(minioClient).bucketExists(captor.capture());
        assertEquals("some-other-bucket", captor.getValue().bucket());
    }

    @Test
    void degraded_andProbeThrows_staysDegraded_andNothingEscapes() throws Exception {
        degrade("upload: ConnectException");
        when(minioClient.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new ConnectException("Connection refused"));

        // A scheduled method that throws buys a scheduler stack trace and nothing else.
        assertDoesNotThrow(() -> probe.recheck());

        assertFalse(healthStatus.isAvailable());
        assertEquals("upload: ConnectException", healthStatus.getReason(),
                "a failed probe must not overwrite the reason the outage started with");
    }

    @Test
    void degraded_andProbeThrowsRuntimeException_stillDoesNotEscape() throws Exception {
        degrade("object stat: IOException");
        when(minioClient.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new IllegalStateException("client closed"));

        assertDoesNotThrow(() -> probe.recheck());

        assertFalse(healthStatus.isAvailable());
    }

    @Test
    void healthy_neverTouchesTheClient() {
        healthStatus.markUp();

        probe.recheck();

        // The steady state is the common case: a healthy deployment must not pay one MinIO
        // round-trip every 30 seconds for a flag that is already correct.
        verifyNoInteractions(minioClient);
        assertTrue(healthStatus.isAvailable());
    }

    @Test
    void degraded_andBucketMissing_stillMarksUp_becauseTheConnectionIsProvenHealthy() throws Exception {
        degrade("upload: IOException");
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        probe.recheck();

        assertTrue(healthStatus.isAvailable(),
                "MinIO answered — 'that bucket is gone' is an S3-level fact, not an outage");
    }

    @Test
    void repeatedFailures_keepProbing_andTheFirstSuccessRecovers() throws Exception {
        degrade("upload: IOException");
        when(minioClient.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new IOException("connection reset"))
                .thenThrow(new IOException("connection reset"))
                .thenReturn(true);

        probe.recheck();
        assertFalse(healthStatus.isAvailable());
        probe.recheck();
        assertFalse(healthStatus.isAvailable());
        probe.recheck();

        assertTrue(healthStatus.isAvailable());
        // A fourth tick, now healthy, must not call out at all — the count stays at 3.
        probe.recheck();
        verify(minioClient, times(3)).bucketExists(any(BucketExistsArgs.class));
    }

    // =======================================================================================
    // The probe must be cheap to FAIL, not just cheap to pass. It shares Spring's scheduling
    // pool with the Telegram log forwarder and the incident monitors; a blackholed MinIO
    // (packets dropped, not refused) would otherwise park a pool thread for minio-java's
    // 5-minute default, for the whole outage — silencing the alerting meant to report it.
    // =======================================================================================

    @Test
    void probeUsesTheShortTimeoutClient_notTheSharedUploadClient() throws Exception {
        MinioClient sharedClient = mock(MinioClient.class);
        MinioClient shortTimeoutProbeClient = mock(MinioClient.class);
        when(shortTimeoutProbeClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        var probeWithBothClients =
                new MinioHealthProbe(shortTimeoutProbeClient, properties, healthStatus);
        degrade("upload: ConnectException");

        probeWithBothClients.recheck();

        verify(shortTimeoutProbeClient).bucketExists(any(BucketExistsArgs.class));
        // The upload/download client keeps its long timeouts for multi-gigabyte transfers and is
        // never used for probing.
        verifyNoInteractions(sharedClient);
        assertTrue(healthStatus.isAvailable());
    }

    @Test
    void probeClientBean_carriesTheShortTimeouts() throws Exception {
        // MinioConfig is where the timeout actually gets set; assert it on the built bean rather
        // than trusting the constant. minio-java keeps its OkHttpClient in a protected S3Base
        // field, which is the only way to observe the configured timeouts.
        var config = new MinioConfig();
        var props = new MinioProperties();

        okhttp3.OkHttpClient probeHttp = httpClientOf(config.minioProbeClient(props));
        okhttp3.OkHttpClient uploadHttp = httpClientOf(config.minioClient(props));

        assertEquals((int) MinioConfig.PROBE_TIMEOUT_MS, probeHttp.connectTimeoutMillis());
        assertEquals((int) MinioConfig.PROBE_TIMEOUT_MS, probeHttp.readTimeoutMillis());
        assertEquals((int) MinioConfig.PROBE_TIMEOUT_MS, probeHttp.writeTimeoutMillis());
        assertTrue(MinioConfig.PROBE_TIMEOUT_MS <= 5000,
                "a probe that takes longer than a few seconds to fail defeats its own purpose");

        // And the shared client is NOT narrowed — it carries whole video files.
        assertTrue(uploadHttp.readTimeoutMillis() > MinioConfig.PROBE_TIMEOUT_MS,
                "the upload/download client must keep its long timeouts");
    }

    @Test
    void metadataClientBean_carriesTheShortTimeouts_forSyncsExistenceChecks() throws Exception {
        // VG-07: /sync stats every file it offers. On the upload client's 5-minute defaults a
        // blackholed MinIO parked the request — and, before the phase split, the pooled database
        // connection with it — so 20 devices taking a campaign together drained the pool.
        var config = new MinioConfig();
        var props = new MinioProperties();

        okhttp3.OkHttpClient metadataHttp = httpClientOf(config.minioMetadataClient(props));
        okhttp3.OkHttpClient uploadHttp = httpClientOf(config.minioClient(props));

        assertEquals((int) MinioConfig.METADATA_TIMEOUT_MS, metadataHttp.connectTimeoutMillis());
        assertEquals((int) MinioConfig.METADATA_TIMEOUT_MS, metadataHttp.readTimeoutMillis());
        assertEquals((int) MinioConfig.METADATA_TIMEOUT_MS, metadataHttp.writeTimeoutMillis());
        assertTrue(MinioConfig.METADATA_TIMEOUT_MS <= 5000,
                "a stat that takes longer than a few seconds to fail is a hung /sync");
        assertTrue(uploadHttp.readTimeoutMillis() > MinioConfig.METADATA_TIMEOUT_MS,
                "transfers must keep their long timeouts — this bean is stat-only");
    }

    /**
     * MinioClient does not extend S3Base in 8.5.x — it wraps a private MinioAsyncClient, which
     * does. Two hops, both private, which is why this lives in a named helper.
     */
    private static okhttp3.OkHttpClient httpClientOf(MinioClient client) throws Exception {
        var asyncField = MinioClient.class.getDeclaredField("asyncClient");
        asyncField.setAccessible(true);
        Object async = asyncField.get(client);
        var httpField = io.minio.S3Base.class.getDeclaredField("httpClient");
        httpField.setAccessible(true);
        return (okhttp3.OkHttpClient) httpField.get(async);
    }

    // =======================================================================================
    // app.minio.recheck-interval is read twice — bound onto MinioProperties and by the
    // @Scheduled placeholder — and a bare number is MILLISECONDS to both. A silly value must
    // fail startup naming the property, not quietly hammer a dead MinIO 33 times a second.
    // =======================================================================================

    @Test
    void absurdRecheckInterval_failsStartup_namingTheProperty() {
        properties.setRecheckInterval(java.time.Duration.ofMillis(30));   // "30" meant as seconds

        var e = assertThrows(IllegalConfigurationException.class,
                () -> new MinioHealthProbe(minioClient, properties, healthStatus));
        assertTrue(e.getMessage().contains("app.minio.recheck-interval"), e.getMessage());
        assertTrue(e.getMessage().contains("MILLISECONDS"), e.getMessage());
    }

    @Test
    void nullRecheckInterval_failsStartup() {
        properties.setRecheckInterval(null);

        assertThrows(IllegalConfigurationException.class,
                () -> new MinioHealthProbe(minioClient, properties, healthStatus));
    }

    @Test
    void theFloorItselfIsAccepted() {
        properties.setRecheckInterval(MinioHealthProbe.MIN_RECHECK_INTERVAL);

        assertDoesNotThrow(() -> new MinioHealthProbe(minioClient, properties, healthStatus));
    }
}
