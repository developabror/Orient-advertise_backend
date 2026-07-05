package uz.orientadvertise.services.infra.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class MinioBucketInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MinioBucketInitializer.class);

    private final MinioClient minioClient;
    private final MinioProperties properties;
    private final MinioHealthStatus healthStatus;

    public MinioBucketInitializer(MinioClient minioClient, MinioProperties properties, MinioHealthStatus healthStatus) {
        this.minioClient = minioClient;
        this.properties = properties;
        this.healthStatus = healthStatus;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (var bucket : properties.getBuckets()) {
                boolean exists = minioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("Created MinIO bucket: {}", bucket);
                } else {
                    log.info("MinIO bucket already exists: {}", bucket);
                }
            }
            healthStatus.markUp();
            log.info("MinIO storage is available — status: UP");
        } catch (Exception e) {
            healthStatus.markDegraded();
            log.warn("MinIO unavailable at startup — storage endpoints will be DEGRADED: {}", e.getMessage());
        }
    }
}
