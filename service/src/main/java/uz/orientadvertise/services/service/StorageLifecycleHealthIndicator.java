package uz.orientadvertise.services.service;

import org.springframework.stereotype.Component;
import uz.orientadvertise.services.common.util.DateUtils;
import uz.orientadvertise.services.domain.model.HealthStatus;
import uz.orientadvertise.services.infra.storage.StorageLifecycleStatus;

/**
 * Publishes whether object-storage cleanup is actually installed.
 *
 * <p>This component exists because the previous failure was invisible. The raw bucket's lifecycle
 * rule failed MinIO's validation on <b>every boot</b> for the life of the deployment, announced by a
 * single WARN that scrolled past — so operators believed application-managed cleanup was running
 * while the stored config was zero bytes and the volume climbed to 96% full. "Cleanup silently does
 * nothing" needs to be visible on the same surface an operator already checks.
 *
 * <p>{@code PENDING} is reported as UP on purpose: it means the install has not been attempted yet
 * (pre-{@code ApplicationReadyEvent}), or MinIO was degraded when we tried — in which case the
 * {@code minio}-related failure is the real signal and flagging this too would be noise.
 */
@Component
public class StorageLifecycleHealthIndicator {

    private static final String COMPONENT = "storage-lifecycle";

    private final StorageLifecycleStatus lifecycleStatus;

    public StorageLifecycleHealthIndicator(StorageLifecycleStatus lifecycleStatus) {
        this.lifecycleStatus = lifecycleStatus;
    }

    public HealthStatus check() {
        var state = lifecycleStatus.getState();
        var status = state == StorageLifecycleStatus.State.FAILED
                ? new HealthStatus.Status.Down(lifecycleStatus.getDetail())
                : new HealthStatus.Status.Up();
        return new HealthStatus(COMPONENT, status, DateUtils.nowIso());
    }
}
