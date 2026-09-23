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
     * Connect/write/read timeout for the health-probe client, in milliseconds. Short on purpose —
     * see {@link #minioProbeClient(MinioProperties)}.
     */
    static final long PROBE_TIMEOUT_MS = 3000L;

    /**
     * Connect/write/read timeout for the metadata client, in milliseconds — see
     * {@link #minioMetadataClient(MinioProperties)}.
     */
    static final long METADATA_TIMEOUT_MS = 3000L;

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
    /**
     * Health-probe-only client, built against the same internal endpoint as the primary bean but
     * with <b>short timeouts</b>: {@value #PROBE_TIMEOUT_MS} ms each for connect, write and read.
     *
     * <p>Why a separate client. {@link MinioHealthProbe} runs on Spring's shared scheduling pool,
     * which also carries the Telegram log forwarder, the sync-timeout monitor, the transcode
     * sweeper and a dozen other jobs. minio-java's OkHttp defaults are <b>5 minutes</b>, and a
     * <em>blackholed</em> MinIO — packets dropped rather than refused, which is what a crashed
     * host, a full conntrack table or a dropped firewall rule looks like — makes every probe hang
     * for the whole timeout. That would park a scheduler thread for five minutes at a time for the
     * duration of the outage, so the alerting that is supposed to tell an operator about the
     * outage stops running too. A probe must be cheap to fail.
     *
     * <p>The primary client keeps the long defaults on purpose: it carries multi-gigabyte uploads
     * and downloads, where three seconds is nothing.
     */
    @Bean("minioProbeClient")
    public MinioClient minioProbeClient(MinioProperties properties) {
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getUrl())
                .region(properties.getRegion())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        client.setTimeout(PROBE_TIMEOUT_MS, PROBE_TIMEOUT_MS, PROBE_TIMEOUT_MS);
        return client;
    }

    /**
     * Metadata-only client: same internal endpoint as the primary bean, but with the probe's short
     * timeouts. Used for {@code statObject} existence checks, never for transfers.
     *
     * <p>Why. Every {@code /sync} stats each file it is about to offer. On the primary client those
     * calls inherit minio-java's <b>5-minute</b> OkHttp defaults, so a blackholed MinIO parks the
     * request — and, before VG-07, the pooled database connection {@code /sync} was holding — for
     * minutes at a time. Twenty devices taking a new campaign together then drained the pool and
     * every other request in that window failed. A stat is a few bytes; if it has not answered in
     * three seconds the store is not healthy, and a 503 is the truthful answer.
     *
     * <p>The primary client keeps the long defaults: it carries multi-gigabyte transfers, where
     * three seconds is nothing. <b>Never</b> use this bean for uploads or downloads.
     */
    @Bean("minioMetadataClient")
    public MinioClient minioMetadataClient(MinioProperties properties) {
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getUrl())
                .region(properties.getRegion())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        client.setTimeout(METADATA_TIMEOUT_MS, METADATA_TIMEOUT_MS, METADATA_TIMEOUT_MS);
        return client;
    }

    @Bean("minioPresignClient")
    public MinioClient minioPresignClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getPublicUrl())
                .region(properties.getRegion())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
