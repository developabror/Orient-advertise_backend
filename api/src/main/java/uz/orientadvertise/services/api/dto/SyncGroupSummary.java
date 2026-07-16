package uz.orientadvertise.services.api.dto;

import java.time.Instant;

import uz.orientadvertise.services.domain.model.SyncGroup;
import uz.orientadvertise.services.service.SyncGroupManagementService.SyncGroupView;

/**
 * Listing projection for {@code GET /api/sync-groups}. Mirrors {@link DeviceGroupSummary}
 * shape-for-shape (minus volume). {@code deviceCount} comes from a batched aggregate keyed by
 * the page's group ids — see
 * {@link uz.orientadvertise.services.domain.repository.DeviceRepository#countActiveDevicesPerSyncGroup}.
 *
 * <p>The FE row parser hard-requires {@code id}/{@code projectId}/{@code deviceCount} as finite
 * numbers and {@code name}/{@code createdAt} as strings — a missing {@code deviceCount} silently
 * drops the row, so it is always populated (zero-filled for empty groups).
 */
public record SyncGroupSummary(
        Long id,
        Long projectId,
        String projectName,
        String name,
        long deviceCount,
        Instant createdAt
) {
    public static SyncGroupSummary from(SyncGroupView view) {
        SyncGroup g = view.group();
        return new SyncGroupSummary(
                g.getId(),
                g.getProject() != null ? g.getProject().getId() : null,
                g.getProject() != null ? g.getProject().getName() : null,
                g.getName(),
                view.deviceCount(),
                g.getCreatedAt());
    }
}
