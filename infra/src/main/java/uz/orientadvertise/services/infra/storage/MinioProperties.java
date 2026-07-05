package uz.orientadvertise.services.infra.storage;

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
    private int orphanCleanupAfterHours = 24;

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

    public int getOrphanCleanupAfterHours() {
        return orphanCleanupAfterHours;
    }

    public void setOrphanCleanupAfterHours(int orphanCleanupAfterHours) {
        this.orphanCleanupAfterHours = orphanCleanupAfterHours;
    }
}
