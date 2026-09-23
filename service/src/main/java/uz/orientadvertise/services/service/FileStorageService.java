package uz.orientadvertise.services.service;

import java.io.InputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uz.orientadvertise.services.domain.storage.StorageClient;

@Service
public class FileStorageService {

    private static final String DEFAULT_BUCKET = "uploads";
    private static final String PROCESSED_BUCKET = "content-processed";

    private final StorageClient storageClient;
    private final String thumbnailBucket;

    public FileStorageService(StorageClient storageClient,
                                @Value("${app.minio.thumbnail-bucket:content-thumbnails}") String thumbnailBucket) {
        this.storageClient = storageClient;
        this.thumbnailBucket = thumbnailBucket;
    }

    public void upload(String objectName, InputStream data, long size, String contentType) {
        storageClient.upload(DEFAULT_BUCKET, objectName, data, size, contentType);
    }

    public InputStream download(String objectName) {
        return storageClient.download(DEFAULT_BUCKET, objectName);
    }

    public String generatePresignedUrl(String objectName) {
        return storageClient.generatePresignedUrl(DEFAULT_BUCKET, objectName);
    }

    public String presignedProcessedUrl(String objectName) {
        return storageClient.generatePresignedUrl(PROCESSED_BUCKET, objectName);
    }

    public String presignedProcessedUrl(String objectName, int expiryMinutes) {
        return storageClient.generatePresignedUrl(PROCESSED_BUCKET, objectName, expiryMinutes);
    }

    public String presignedThumbnailUrl(String objectName, int expiryMinutes) {
        return storageClient.generatePresignedUrl(thumbnailBucket, objectName, expiryMinutes);
    }

    public boolean processedObjectExists(String objectName) {
        return storageClient.exists(PROCESSED_BUCKET, objectName);
    }

    public void delete(String objectName) {
        storageClient.delete(DEFAULT_BUCKET, objectName);
    }

    /**
     * Remove a processed MP4. Only two things may call this: the sweeper that reclaims a
     * soft-deleted file's bytes, and a retranscode superseding its own previous output — a device
     * downloads from this bucket, so deleting an object a live playlist still lists blanks that
     * slot on every screen holding it.
     */
    public void deleteProcessed(String objectName) {
        storageClient.delete(PROCESSED_BUCKET, objectName);
    }

    /** Remove a poster JPEG. Same rule as {@link #deleteProcessed}. */
    public void deleteThumbnail(String objectName) {
        storageClient.delete(thumbnailBucket, objectName);
    }

    public boolean isStorageAvailable() {
        return storageClient.isAvailable();
    }
}
