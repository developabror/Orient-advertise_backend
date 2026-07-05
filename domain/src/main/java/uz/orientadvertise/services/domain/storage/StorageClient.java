package uz.orientadvertise.services.domain.storage;

import java.io.InputStream;

public interface StorageClient {

    void upload(String bucket, String objectName, InputStream data, long size, String contentType);

    InputStream download(String bucket, String objectName);

    String generatePresignedUrl(String bucket, String objectName);

    String generatePresignedUrl(String bucket, String objectName, int expiryMinutes);

    boolean exists(String bucket, String objectName);

    void delete(String bucket, String objectName);

    boolean isAvailable();
}
