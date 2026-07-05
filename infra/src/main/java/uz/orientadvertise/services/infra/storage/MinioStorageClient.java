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
            healthStatus.markDegraded();
            throw new StorageUnavailableException("Upload failed: " + e.getMessage());
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
            // Marking degraded here would let one bad key flip the whole storage layer to
            // DEGRADED and 503 every subsequent request (self-DoS).
            String code = e.errorResponse() == null ? null : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchBucket".equals(code)) {
                throw new ResourceNotFoundException("File", objectName);
            }
            healthStatus.markDegraded();
            throw new StorageUnavailableException("Download failed: " + e.getMessage());
        } catch (Exception e) {
            healthStatus.markDegraded();
            throw new StorageUnavailableException("Download failed: " + e.getMessage());
        }
    }

    @Override
    public String generatePresignedUrl(String bucket, String objectName) {
        return generatePresignedUrl(bucket, objectName, properties.getPresignedUrlExpiryMinutes());
    }

    @Override
    public String generatePresignedUrl(String bucket, String objectName, int expiryMinutes) {
        ensureAvailable();
        try {
            // Use the presign-only client so the URL embeds the *externally reachable*
            // host (app.minio.public-url) rather than the docker-network hostname the
            // backend uses for direct calls. With the region preconfigured on the
            // presign client (see MinioConfig), getPresignedObjectUrl is pure local
            // crypto — no network traffic against the public URL — so it can't fail
            // for connectivity reasons. We deliberately do NOT call markDegraded()
            // here: a presign failure with region preset is an arg/programming bug,
            // not a MinIO-is-down signal, so it must not cascade and disable uploads.
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectName)
                    .expiry(expiryMinutes, TimeUnit.MINUTES)
                    .build());
        } catch (Exception e) {
            throw new StorageUnavailableException("Presigned URL generation failed: " + e.getMessage());
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
            String code = e.errorResponse() == null ? null : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchBucket".equals(code)) {
                return false;
            }
            healthStatus.markDegraded();
            throw new StorageUnavailableException("Object stat failed: " + e.getMessage());
        } catch (Exception e) {
            healthStatus.markDegraded();
            throw new StorageUnavailableException("Object stat failed: " + e.getMessage());
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
            healthStatus.markDegraded();
            throw new StorageUnavailableException("Delete failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        return healthStatus.isAvailable();
    }

    private void ensureAvailable() {
        if (!healthStatus.isAvailable()) {
            throw new StorageUnavailableException("MinIO storage is currently unavailable");
        }
    }
}
