package uz.orientadvertise.services.infra.storage;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.common.exception.StorageUnavailableException;
import uz.orientadvertise.services.domain.storage.StorageClient;

@Component
public class MinioStorageClient implements StorageClient {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageClient.class);

    /**
     * Message handed to the CALLER on any storage failure. Deliberately fixed text: since
     * v1.0.144 a storage failure reaches devices through {@code /sync} and {@code /playlist}, and
     * MinIO's own messages name the endpoint, bucket and object key. A TV box gets "try again",
     * the log gets the detail.
     */
    static final String CLIENT_MESSAGE = "Object storage is temporarily unavailable";

    private final MinioClient minioClient;
    private final MinioClient presignClient;
    private final MinioProperties properties;
    private final MinioHealthStatus healthStatus;

    public MinioStorageClient(MinioClient minioClient,
                              @Qualifier("minioPresignClient") MinioClient presignClient,
                              MinioProperties properties,
                              MinioHealthStatus healthStatus) {
        this.minioClient = minioClient;
        this.presignClient = presignClient;
        this.properties = properties;
        this.healthStatus = healthStatus;
    }

    @Override
    public void upload(String bucket, String objectName, InputStream data, long size, String contentType) {
        ensureAvailable();
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(data, size, -1)
                    .contentType(contentType)
                    .build());
            log.debug("Uploaded object [bucket={}, key={}]", bucket, objectName);
        } catch (Exception e) {
            throw storageFailure("upload", bucket, objectName, e);
        }
    }

    @Override
    public InputStream download(String bucket, String objectName) {
        ensureAvailable();
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
        } catch (ErrorResponseException e) {
            // A genuinely-missing object is a 404, NOT a MinIO outage — mirror exists().
            if (isMissing(e)) {
                throw new ResourceNotFoundException("File", objectName);
            }
            throw storageFailure("download", bucket, objectName, e);
        } catch (Exception e) {
            throw storageFailure("download", bucket, objectName, e);
        }
    }

    @Override
    public String generatePresignedUrl(String bucket, String objectName) {
        return generatePresignedUrl(bucket, objectName, properties.getPresignedUrlExpiryMinutes());
    }

    @Override
    public String generatePresignedUrl(String bucket, String objectName, int expiryMinutes) {
        // Deliberately NO ensureAvailable(): with the region preconfigured on the presign client
        // (see MinioConfig) signing is pure local crypto — an HMAC over strings — and works
        // perfectly while MinIO itself is unreachable. Gating it on the health flag was how one
        // blip turned into "every device gets a short playlist": /sync could no longer mint URLs
        // for files it was otherwise able to describe. The URL is useless until MinIO answers
        // again, but minting it costs nothing and it is valid for its whole TTL.
        try {
            // Use the presign-only client so the URL embeds the *externally reachable*
            // host (app.minio.public-url) rather than the docker-network hostname the
            // backend uses for direct calls. We deliberately do NOT call markDegraded()
            // here: a presign failure with region preset is an arg/programming bug,
            // not a MinIO-is-down signal, so it must not cascade and disable uploads.
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectName)
                    .expiry(expiryMinutes, TimeUnit.MINUTES)
                    .build());
        } catch (Exception e) {
            // Same rule as storageFailure: fixed text out, detail in the log. Not markDegraded —
            // with the region preset this is an argument bug, not an availability signal.
            log.info("MinIO presign failed for [bucket={}, key={}] — {}: {}",
                    bucket, objectName, e.getClass().getSimpleName(), e.getMessage());
            throw new StorageUnavailableException(CLIENT_MESSAGE);
        }
    }

    @Override
    public boolean exists(String bucket, String objectName) {
        ensureAvailable();
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            // NoSuchKey / NoSuchBucket → object truly absent. Other error codes (auth, etc.)
            // are surfaced as a storage failure so the caller can react accordingly.
            if (isMissing(e)) {
                return false;
            }
            throw storageFailure("object stat", bucket, objectName, e);
        } catch (Exception e) {
            throw storageFailure("object stat", bucket, objectName, e);
        }
    }

    @Override
    public void delete(String bucket, String objectName) {
        ensureAvailable();
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
            log.debug("Deleted object [bucket={}, key={}]", bucket, objectName);
        } catch (Exception e) {
            throw storageFailure("delete", bucket, objectName, e);
        }
    }

    @Override
    public boolean isAvailable() {
        return healthStatus.isAvailable();
    }

    /** {@code NoSuchKey} / {@code NoSuchBucket} — the request reached MinIO and it said "not here". */
    private static boolean isMissing(ErrorResponseException e) {
        String code = e.errorResponse() == null ? null : e.errorResponse().code();
        return "NoSuchKey".equals(code) || "NoSuchBucket".equals(code);
    }

    /**
     * Single exit for every MinIO failure: classify, degrade only on a connection-level failure,
     * and hand the caller back the exception to throw.
     *
     * <p>Classification lives in {@link MinioFailureClassifier} so the Telegram {@code /health}
     * probe — the other writer of {@link MinioHealthStatus} — cannot drift from this one.
     *
     * <p>Neither branch logs at WARN. WARN is forwarded to the operator Telegram chat, and both
     * branches are reachable once per request by every device in the fleet: a misconfigured ACL,
     * or an outage that lasts longer than one sync interval. The outage is announced exactly once
     * by {@link MinioHealthStatus#markDegraded(String)} on its transition; the per-request detail
     * is here.
     */
    private StorageUnavailableException storageFailure(String op, String bucket, String objectName, Exception e) {
        String type = e.getClass().getSimpleName();
        if (MinioFailureClassifier.isConnectionLevel(e)) {
            // Reason is published verbatim on the unauthenticated /api/health — type only, no
            // message (which can carry endpoints), no object key (which can carry a filename).
            healthStatus.markDegraded(op + ": " + type);
            log.debug("MinIO {} failed for [bucket={}, key={}] — {}: {}",
                    op, bucket, objectName, type, e.getMessage());
        } else {
            log.info("MinIO {} failed for [bucket={}, key={}] — {}: {} (per-request error; storage "
                    + "stays {})", op, bucket, objectName, type, e.getMessage(), healthStatus.getState());
        }
        return new StorageUnavailableException(CLIENT_MESSAGE);
    }

    private void ensureAvailable() {
        if (!healthStatus.isAvailable()) {
            throw new StorageUnavailableException(CLIENT_MESSAGE);
        }
    }
}
