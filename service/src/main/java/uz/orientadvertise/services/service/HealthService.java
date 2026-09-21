package uz.orientadvertise.services.service;

import java.util.List;

import org.springframework.stereotype.Service;
import uz.orientadvertise.services.common.util.DateUtils;
import uz.orientadvertise.services.domain.model.HealthStatus;
import uz.orientadvertise.services.infra.health.DatabaseHealthIndicator;

@Service
public class HealthService {

    private final DatabaseHealthIndicator databaseHealthIndicator;
    private final TranscodeBacklogHealthIndicator transcodeBacklogHealthIndicator;
    private final DiskFreeHealthIndicator diskFreeHealthIndicator;
    private final StorageLifecycleHealthIndicator storageLifecycleHealthIndicator;
    private final StorageHealthIndicator storageHealthIndicator;

    public HealthService(DatabaseHealthIndicator databaseHealthIndicator,
                          TranscodeBacklogHealthIndicator transcodeBacklogHealthIndicator,
                          DiskFreeHealthIndicator diskFreeHealthIndicator,
                          StorageLifecycleHealthIndicator storageLifecycleHealthIndicator,
                          StorageHealthIndicator storageHealthIndicator) {
        this.databaseHealthIndicator = databaseHealthIndicator;
        this.transcodeBacklogHealthIndicator = transcodeBacklogHealthIndicator;
        this.diskFreeHealthIndicator = diskFreeHealthIndicator;
        this.storageLifecycleHealthIndicator = storageLifecycleHealthIndicator;
        this.storageHealthIndicator = storageHealthIndicator;
    }

    /**
     * Full component roll-up for {@code GET /api/health}. {@code overallStatus} degrades if any
     * component is down.
     *
     * <p>Every component here answers a question that was once <b>unobservable</b> in production:
     * <ul>
     *   <li>{@code transcode-backlog} — the app is up but uploads are silently not being processed
     *       (v1.0.132)</li>
     *   <li>{@code disk} — the one shared volume is filling, and when it fills Postgres, MinIO and
     *       the container overlay go together (v1.0.133)</li>
     *   <li>{@code storage-lifecycle} — automated cleanup is not actually installed, so nothing is
     *       reclaiming space (v1.0.133)</li>
     *   <li>{@code storage} — MinIO is unreachable, so uploads 503, transcodes fail and devices
     *       cannot sync. Absent from this endpoint until v1.0.144, which is also when the flag
     *       behind it stopped being a latch only a restart could clear (v1.0.144)</li>
     * </ul>
     * That is what {@code DEGRADED} is for: the service is serving traffic correctly while something
     * behind it is quietly broken.
     */
    public List<HealthStatus> checkAll() {
        var appStatus = new HealthStatus(
                "application",
                new HealthStatus.Status.Up(),
                DateUtils.nowIso()
        );
        return List.of(
                appStatus,
                databaseHealthIndicator.check(),
                transcodeBacklogHealthIndicator.check(),
                diskFreeHealthIndicator.check(),
                storageLifecycleHealthIndicator.check(),
                storageHealthIndicator.check());
    }

    public HealthStatus checkApplication() {
        return new HealthStatus("application", new HealthStatus.Status.Up(), DateUtils.nowIso());
    }
}
