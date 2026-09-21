package uz.orientadvertise.services.service;

import org.springframework.stereotype.Component;
import uz.orientadvertise.services.common.util.DateUtils;
import uz.orientadvertise.services.domain.model.HealthStatus;
import uz.orientadvertise.services.infra.storage.MinioHealthStatus;

/**
 * Publishes whether object storage is reachable.
 *
 * <p>Until v1.0.144 MinIO was absent from {@code GET /api/health} entirely — the one dependency
 * whose loss turns every upload into a 503, every transcode into a FAILED row and every device
 * {@code /sync} into a retry, and the health surface said nothing about it. The only way to learn
 * that storage had latched DEGRADED was to try an upload and read the 503, or to ask Telegram.
 *
 * <p>Reports {@link MinioHealthStatus.State#DEGRADED} as DOWN and carries the reason plus how long
 * it has been that way, because "storage is down" and "storage has been down for four hours" call
 * for different operator responses. The reason is a short operation-plus-exception-type string
 * produced by the storage layer — never a raw exception message — because this endpoint is
 * unauthenticated (same rule as {@link DiskFreeHealthIndicator}).
 *
 * <p>A freshly-started process reports DOWN with {@code "not probed yet (startup)"} until
 * {@code MinioBucketInitializer} runs. That is honest rather than noisy: storage endpoints really
 * do 503 in that window.
 */
@Component
public class StorageHealthIndicator {

    private static final String COMPONENT = "storage";

    private final MinioHealthStatus storageStatus;

    public StorageHealthIndicator(MinioHealthStatus storageStatus) {
        this.storageStatus = storageStatus;
    }

    public HealthStatus check() {
        var state = storageStatus.getState();
        var status = state == MinioHealthStatus.State.UP
                ? new HealthStatus.Status.Up()
                : new HealthStatus.Status.Down(storageStatus.getReason()
                        + " (since " + DateUtils.toIso(storageStatus.getSince()) + ")");
        return new HealthStatus(COMPONENT, status, DateUtils.nowIso());
    }
}
