package uz.orientadvertise.services.infra.storage;

import java.util.List;

import io.minio.MinioClient;
import io.minio.SetBucketLifecycleArgs;
import io.minio.messages.AbortIncompleteMultipartUpload;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Configures the raw bucket's lifecycle policy so MinIO automatically aborts
 * incomplete multipart uploads after the configured threshold (default 24h).
 *
 * Edge case handled: TV-Box uploads a large file, network drops mid-stream.
 * MinIO retains parts indefinitely otherwise — the lifecycle rule
 * {@code AbortIncompleteMultipartUpload} sweeps stale parts on the server side
 * without us needing a periodic application job.
 */
@Component
public class OrphanedUploadCleaner {

    private static final Logger log = LoggerFactory.getLogger(OrphanedUploadCleaner.class);

    private final MinioClient minioClient;
    private final MinioProperties properties;
    private final MinioHealthStatus healthStatus;

    public OrphanedUploadCleaner(MinioClient minioClient, MinioProperties properties,
                                  MinioHealthStatus healthStatus) {
        this.minioClient = minioClient;
        this.properties = properties;
        this.healthStatus = healthStatus;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void installLifecyclePolicy() {
        if (!healthStatus.isAvailable()) {
            log.debug("Skipping lifecycle install — MinIO is degraded");
            return;
        }

        var bucket = properties.getRawBucket();
        var afterDays = Math.max(1, properties.getOrphanCleanupAfterHours() / 24);

        try {
            var rule = new LifecycleRule(
                    Status.ENABLED,
                    new AbortIncompleteMultipartUpload(afterDays),
                    null,
                    new RuleFilter(""),
                    "orphan-multipart-cleanup",
                    null, null, null);

            var config = new LifecycleConfiguration(List.of(rule));
            minioClient.setBucketLifecycle(SetBucketLifecycleArgs.builder()
                    .bucket(bucket)
                    .config(config)
                    .build());

            log.info("Installed orphaned-upload lifecycle policy on [{}]: abort incomplete multipart after {} day(s)",
                    bucket, afterDays);
        } catch (Exception e) {
            log.warn("Failed to install orphan cleanup lifecycle on [{}] (non-critical): {}",
                    bucket, e.getMessage());
        }
    }
}
