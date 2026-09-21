package uz.orientadvertise.services.infra.storage;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.minio")
public class MinioProperties {

    private String url = "http://localhost:9000";
    /**
     * Externally reachable MinIO endpoint baked into presigned URLs handed to browsers
     * and TV-Box players. Distinct from {@link #url}, which is the address the backend
     * uses to talk to MinIO over the docker network (e.g. {@code http://minio:9000}).
     * When the backend runs in docker and clients run on the host, {@code url} would
     * resolve only inside the network — set this to the host-reachable form
     * (e.g. {@code http://localhost:9000}). Defaults to {@link #url} when unset.
     */
    private String publicUrl;
    /**
     * S3 region preconfigured on every {@link io.minio.MinioClient}. MinIO uses
     * {@code us-east-1} by default. Setting this here is load-bearing: without it,
     * the SDK lazily discovers the region by firing an HTTP HEAD at the endpoint
     * on the first call — and the presign client's endpoint
     * ({@link #getPublicUrl()}) is, by design, unreachable from the backend
     * (e.g. {@code http://localhost:9000} inside docker). That probe would fail,
     * trip {@code healthStatus.markDegraded()}, and cascade every subsequent
     * storage call to "MinIO storage is currently unavailable". Preconfiguring
     * the region keeps presigning a pure local crypto op.
     */
    private String region = "us-east-1";
    private String accessKey = "minioadmin";
    private String secretKey = "minioadmin";
    private List<String> buckets = List.of(
            "uploads", "content-raw", "content-processed", "content-thumbnails");
    private int presignedUrlExpiryMinutes = 60;
    private String rawBucket = "content-raw";
    private String thumbnailBucket = "content-thumbnails";
    /**
     * Days after upload that a raw original under {@code raw/} is deleted by MinIO's lifecycle
     * engine. {@code 0} disables installation entirely.
     *
     * <p>Replaces the former {@code orphan-cleanup-after-hours}, whose abort-incomplete-multipart
     * rule MinIO rejected on every boot (see {@link RawBucketLifecycleInstaller}). MinIO sweeps
     * stale multipart uploads itself via {@code stale_uploads_expiry}, so nothing is lost by
     * dropping that action — and unlike it, this rule actually installs and actually reclaims space.
     *
     * <p>The processed MP4 and the poster are NEVER expired; only the source is. The cost of a short
     * window is that {@code POST /api/content/{id}/retranscode} stops working once the source is
     * gone.
     */
    private int rawExpiryDays = 30;
    /**
     * How often {@link MinioHealthProbe} re-probes MinIO <em>while storage is degraded</em>. When
     * storage is healthy the probe costs one field read per tick and issues no MinIO call at all,
     * so this is purely "how fast does an outage heal", not a polling budget.
     *
     * <p>Before v1.0.144 the answer was "never": {@link MinioHealthStatus} could only be set back
     * to UP by the startup {@link MinioBucketInitializer}, so any blip 503'd storage until someone
     * restarted the application.
     *
     * <p><b>Write it as an ISO-8601 duration</b> ({@code PT30S}, {@code PT2M}). This value is read
     * twice — once bound here, once by {@code @Scheduled(fixedDelayString = ...)} — and the two
     * readers agree on every input <em>only</em> because neither declares a unit: a bare number is
     * milliseconds to both. Adding {@code @DurationUnit} here would make {@code 30} mean 30 seconds
     * to this field and 30 milliseconds to the scheduler that actually drives the probe.
     */
    private Duration recheckInterval = Duration.ofSeconds(30);

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * @return the explicit public URL when configured, otherwise the internal
     *         {@link #url} — so callers never have to null-check.
     */
    public String getPublicUrl() {
        return publicUrl == null || publicUrl.isBlank() ? url : publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public List<String> getBuckets() {
        return buckets;
    }

    public void setBuckets(List<String> buckets) {
        this.buckets = buckets;
    }

    public int getPresignedUrlExpiryMinutes() {
        return presignedUrlExpiryMinutes;
    }

    public void setPresignedUrlExpiryMinutes(int presignedUrlExpiryMinutes) {
        this.presignedUrlExpiryMinutes = presignedUrlExpiryMinutes;
    }

    public String getRawBucket() {
        return rawBucket;
    }

    public void setRawBucket(String rawBucket) {
        this.rawBucket = rawBucket;
    }

    public String getThumbnailBucket() {
        return thumbnailBucket;
    }

    public void setThumbnailBucket(String thumbnailBucket) {
        this.thumbnailBucket = thumbnailBucket;
    }

    public int getRawExpiryDays() {
        return rawExpiryDays;
    }

    public void setRawExpiryDays(int rawExpiryDays) {
        this.rawExpiryDays = rawExpiryDays;
    }

    public Duration getRecheckInterval() {
        return recheckInterval;
    }

    public void setRecheckInterval(Duration recheckInterval) {
        this.recheckInterval = recheckInterval;
    }
}
