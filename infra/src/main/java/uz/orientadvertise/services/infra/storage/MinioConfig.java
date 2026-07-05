package uz.orientadvertise.services.infra.storage;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    /**
     * Primary client — built against {@code app.minio.url}, the address the backend
     * uses to reach MinIO directly (over the docker network in compose). All upload /
     * download / stat / delete calls go through this bean.
     */
    @Bean
    @Primary
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getUrl())
                .region(properties.getRegion())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    /**
     * Presigning-only client — built against {@code app.minio.public-url}, which is the
     * externally reachable endpoint browsers and TV-Box players can hit. Presigning is
     * a local crypto op ONLY when a region is preconfigured: without {@code .region(...)},
     * the SDK fires an HTTP HEAD at the endpoint to discover the bucket region, and the
     * public URL is by design unreachable from the backend (e.g. {@code localhost:9000}
     * inside docker). Passing the region here keeps {@code getPresignedObjectUrl} a pure
     * crypto call so the signed Host matches what the browser will actually send.
     */
    @Bean("minioPresignClient")
    public MinioClient minioPresignClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getPublicUrl())
                .region(properties.getRegion())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
