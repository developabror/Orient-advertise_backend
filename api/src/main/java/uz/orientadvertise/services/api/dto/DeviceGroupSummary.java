package uz.orientadvertise.services.api.dto;

import java.time.Instant;

import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.service.DeviceGroupManagementService.DeviceGroupView;

/**
 * Listing projection for {@code GET /api/device-groups}. {@code deviceCount} comes from
 * a batched aggregate keyed by the page's group ids — see
 * {@link uz.orientadvertise.services.domain.repository.DeviceRepository#countActiveDevicesPerGroup}.
 * Soft-deleted devices are excluded from the count.
 */
public record DeviceGroupSummary(
        Long id,
        Long projectId,
        String projectName,
        String name,
        long deviceCount,
        Instant createdAt
) {
    public static DeviceGroupSummary from(DeviceGroupView view) {
        DeviceGroup g = view.group();
        return new DeviceGroupSummary(
                g.getId(),
                g.getProject() != null ? g.getProject().getId() : null,
                g.getProject() != null ? g.getProject().getName() : null,
                g.getName(),
                view.deviceCount(),
                g.getCreatedAt());
    }
}
