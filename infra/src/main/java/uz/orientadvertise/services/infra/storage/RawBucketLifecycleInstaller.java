package uz.orientadvertise.services.infra.storage;

import java.util.List;

import io.minio.MinioClient;
import io.minio.SetBucketLifecycleArgs;
import io.minio.messages.Expiration;
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
 * Installs the {@code content-raw} bucket's expiry rule: raw originals under the {@code raw/} prefix
 * are deleted by MinIO {@code app.minio.raw-expiry-days} after upload.
 *
 * <h2>Why raw originals must expire at all</h2>
 * Nothing ever deleted them. Every successful ad costs roughly 90 MB permanently (raw + processed +
 * poster), on a single 8.1 G volume shared by the container overlay, the MinIO data directory, the
 * Postgres data directory and {@code /swap.img} — which was at 96% full, i.e. about four more 50 MB
 * ads from taking Postgres, MinIO and the overlay down together. The processed MP4 and the poster
 * are kept forever; only the source is reclaimed.
 *
 * <p><b>The one behavioural cost, stated plainly:</b> once a file's raw object has expired,
 * {@code POST /api/content/{id}/retranscode} on it can no longer succeed — there is nothing to
 * transcode from. Playback is unaffected: devices download the processed object, which is untouched.
 * Set {@code app.minio.raw-expiry-days: 0} to disable installation entirely.
 *
 * <h2>Why this replaced {@code OrphanedUploadCleaner}</h2>
 * That class declared {@link io.minio.messages.AbortIncompleteMultipartUpload} as its <em>only</em>
 * action. MinIO's ILM validator requires at least one of
 * {@code Expiration}/{@code Transition}/{@code NoncurrentVersion*}/{@code DelMarkerExpiration}, and
 * the server has no abort type in its lifecycle package at all — so the document failed validation
 * and the install lost to {@code MalformedXML} on <em>every single boot</em>, leaving a zero-length
 * lifecycle config on the bucket and one WARN in the log.
 *
 * <p>Verified against a disposable MinIO on the deployed release
 * ({@code RELEASE.2025-09-07T16-13-09Z}, {@code io.minio:minio:8.5.14}):
 * <ul>
 *   <li>abort-only with an empty filter → {@code MalformedXML}</li>
 *   <li>abort-only with a {@code raw/} prefix → {@code MalformedXML} (so {@code RuleFilter("")} was
 *       <em>not</em> the cause, contrary to the obvious first guess)</li>
 *   <li>expiration + {@code raw/} → installs, and reads back as
 *       {@code status=Enabled prefix=raw/ expirationDays=30 abort=null}</li>
 * </ul>
 * Nothing leaked from the old failure: MinIO sweeps stale multipart uploads itself via
 * {@code stale_uploads_expiry} (24 h, not overridden). The real defect was dead code plus a WARN
 * that trained operators to expect cleanup which did not exist.
 *
 * <h2>Upload flow — the old javadoc had this backwards</h2>
 * Uploads go <b>device/operator → backend → MinIO</b>: {@code MinioStorageClient.putObject} is
 * called with a known size, so the <em>backend</em> is the multipart initiator. No client ever talks
 * to MinIO directly on the ingest path (presigned URLs are read-only, for playback).
 *
 * <p>A failed install is recorded on {@link StorageLifecycleStatus} and surfaced on
 * {@code GET /api/health}, so "automated cleanup is not running" can no longer hide behind one WARN.
 */
@Component
public class RawBucketLifecycleInstaller {

    private static final Logger log = LoggerFactory.getLogger(RawBucketLifecycleInstaller.class);

    /** Rule id stored on the bucket; stable so a re-install replaces rather than accumulates. */
    static final String RULE_ID = "raw-original-expiry";

    /**
     * Only objects under this prefix expire. {@code ContentUploadService} writes every raw object as
     * {@code "raw/" + UUID + "_" + filename}, so the scope is exactly "source uploads" — never the
     * processed MP4s or posters, which live in their own buckets anyway.
     */
    static final String RAW_PREFIX = "raw/";

    private final MinioClient minioClient;
    private final MinioProperties properties;
    private final MinioHealthStatus healthStatus;
    private final StorageLifecycleStatus lifecycleStatus;

    public RawBucketLifecycleInstaller(MinioClient minioClient,
                                        MinioProperties properties,
                                        MinioHealthStatus healthStatus,
                                        StorageLifecycleStatus lifecycleStatus) {
        this.minioClient = minioClient;
        this.properties = properties;
        this.healthStatus = healthStatus;
        this.lifecycleStatus = lifecycleStatus;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void installLifecyclePolicy() {
        var bucket = properties.getRawBucket();
        int days = properties.getRawExpiryDays();

        if (days <= 0) {
            // A deliberate choice, not a failure — but say so out loud, because the consequence is
            // that content-raw grows without bound.
            log.warn("Raw-object expiry is DISABLED (app.minio.raw-expiry-days={}) — [{}] will grow "
                    + "without bound; every upload costs its source bytes permanently", days, bucket);
            lifecycleStatus.markDisabled("raw expiry disabled (app.minio.raw-expiry-days=0)");
            return;
        }

        if (!healthStatus.isAvailable()) {
            // Not a failure of the rule: we never got to try. Distinguishing this from FAILED keeps
            // the health surface honest when MinIO itself is the problem.
            log.info("Deferring raw-expiry lifecycle install on [{}] — MinIO is degraded", bucket);
            lifecycleStatus.markPending("MinIO degraded at startup — lifecycle not installed");
            return;
        }

        try {
            var rule = new LifecycleRule(
                    Status.ENABLED,
                    null,                                        // no abort action — MinIO rejects it
                    new Expiration((java.time.ZonedDateTime) null, days, null),
                    new RuleFilter(RAW_PREFIX),
                    RULE_ID,
                    null, null, null);

            minioClient.setBucketLifecycle(SetBucketLifecycleArgs.builder()
                    .bucket(bucket)
                    .config(new LifecycleConfiguration(List.of(rule)))
                    .build());

            var detail = "expire %s* after %d day(s) on [%s]".formatted(RAW_PREFIX, days, bucket);
            log.info("Installed raw-object expiry lifecycle: {}", detail);
            lifecycleStatus.markInstalled(detail);
        } catch (Exception e) {
            // ERROR, not WARN: a silent WARN here is precisely what let the previous breakage run
            // unnoticed for the life of the deployment.
            log.error("Failed to install raw-expiry lifecycle on [{}] — raw originals will NOT be "
                    + "reclaimed and the volume will keep growing: {}", bucket, e.getMessage());
            lifecycleStatus.markFailed("lifecycle install rejected: " + e.getMessage());
        }
    }
}
