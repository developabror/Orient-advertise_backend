package uz.orientadvertise.services.infra.storage;

import java.time.Duration;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;

/**
 * Re-probes MinIO while it is degraded, so an outage heals itself instead of waiting for a restart.
 *
 * <p><b>Why this exists.</b> {@link MinioHealthStatus} had exactly one writer that could set
 * {@link MinioHealthStatus.State#UP} — {@link MinioBucketInitializer}, at startup. Every other
 * writer could only degrade it. One disk-full moment, one {@code docker restart minio}, one
 * network blip therefore 503'd every upload, failed every transcode and silently truncated every
 * device's {@code /sync} plan <em>for the life of the process</em>. The probe is the missing
 * second writer.
 *
 * <p>Cost when healthy: one field read per tick. The probe does nothing at all unless the status
 * is degraded, so a healthy deployment never issues a single extra MinIO call.
 *
 * <p><b>It must be cheap to FAIL, not just cheap to pass.</b> This runs on Spring's shared
 * scheduling pool alongside the Telegram log forwarder, {@code SyncTimeoutMonitor} and
 * {@code TranscodeSweeper}. A <em>blackholed</em> MinIO (packets dropped, not refused) would hang
 * a probe for minio-java's default 5-minute OkHttp timeout, parking a pool thread for the whole
 * outage and stopping the alerting that is meant to report it. Two things prevent that: this probe
 * uses the short-timeout {@code minioProbeClient} (see {@link MinioConfig#minioProbeClient}), and
 * {@code spring.task.scheduling.pool.size} is 3, not the default 1. {@code fixedDelay} already
 * serialises ticks, so a slow probe can never overlap itself.
 *
 * <p>The probe never throws: a scheduled method that propagates leaves nothing but a scheduler
 * stack trace, and the next tick would try again anyway. Failures stay at DEBUG — the outage was
 * already announced once by {@link MinioHealthStatus#markDegraded(String)}, and re-announcing it
 * every 30 s would flood the operator chat (WARN and above is forwarded to Telegram).
 */
@Component
public class MinioHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(MinioHealthProbe.class);

    /**
     * Floor for {@code app.minio.recheck-interval}. The value is read twice — bound onto
     * {@link MinioProperties} and, separately, by the {@code @Scheduled} placeholder below — and a
     * bare number is milliseconds to both. {@code APP_MINIO_RECHECK_INTERVAL=30}, the obvious way
     * to write "30 seconds", is therefore 30 ms: 33 probes a second against a dead MinIO, each
     * holding a scheduler thread. Spring rejects a non-positive delay by itself; this guard names
     * the property for everything between.
     */
    static final Duration MIN_RECHECK_INTERVAL = Duration.ofSeconds(1);

    private final MinioClient probeClient;
    private final MinioProperties properties;
    private final MinioHealthStatus healthStatus;

    public MinioHealthProbe(@Qualifier("minioProbeClient") MinioClient probeClient,
                            MinioProperties properties,
                            MinioHealthStatus healthStatus) {
        Duration interval = properties.getRecheckInterval();
        if (interval == null || interval.compareTo(MIN_RECHECK_INTERVAL) < 0) {
            throw new IllegalConfigurationException(
                    "app.minio.recheck-interval must be at least " + MIN_RECHECK_INTERVAL
                            + " (got " + interval + "). Write an ISO-8601 duration such as PT30S — "
                            + "a bare number is interpreted as MILLISECONDS.");
        }
        this.probeClient = probeClient;
        this.properties = properties;
        this.healthStatus = healthStatus;
        // Resolved value logged once so the decision is auditable from the boot log.
        log.info("MinIO health probe armed — re-probing [{}] every {} while storage is degraded",
                properties.getRawBucket(), interval);
    }

    @Scheduled(fixedDelayString = "${app.minio.recheck-interval:PT30S}",
               initialDelayString = "${app.minio.recheck-interval:PT30S}")
    public void recheck() {
        if (healthStatus.isAvailable()) {
            return;
        }
        String bucket = properties.getRawBucket();
        try {
            boolean exists = probeClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                // Reachable, answered, bucket gone. That is an S3-level fact about one bucket, not
                // a connectivity failure — the connection is demonstrably healthy, so heal the
                // latch and say the bucket is missing exactly once (the next tick short-circuits).
                log.info("MinIO answered but bucket [{}] does not exist — storage marked UP; "
                        + "recreate the bucket or restart to let MinioBucketInitializer create it", bucket);
            }
            healthStatus.markUp();
        } catch (Exception e) {
            log.debug("MinIO recheck on [{}] still failing: {}: {}",
                    bucket, e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
