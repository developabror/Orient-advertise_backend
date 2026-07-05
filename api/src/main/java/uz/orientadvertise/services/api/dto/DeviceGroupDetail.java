package uz.orientadvertise.services.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.domain.model.DeviceVolumeResolver;
import uz.orientadvertise.services.service.DeviceGroupManagementService.DeviceGroupDetailView;

/**
 * Detail projection for {@code GET /api/device-groups/{id}} and the responses of create
 * / rename. Strict superset of {@link DeviceGroupSummary}: includes the active member
 * device list as {@link DeviceSummary} entries.
 *
 * <p>{@code deviceCount} is sourced from {@code devices.size()} on this projection — the
 * detail path returns the actual member list, so deriving the count from it stays
 * authoritative without a separate query.
 */
public record DeviceGroupDetail(
        Long id,
        Long projectId,
        String projectName,
        String name,
        Integer volume,
        long deviceCount,
        Instant createdAt,
        List<DeviceSummary> devices
) {
    public static DeviceGroupDetail from(DeviceGroupDetailView view,
                                         Map<Long, Device.Status> computedStatuses,
                                         Map<Long, Integer> effectiveVolumes) {
        DeviceGroup g = view.group();
        var devices = view.devices().stream()
                .map(d -> DeviceSummary.from(d, computedStatuses.get(d.getId()),
                        effectiveVolumes.getOrDefault(d.getId(), DeviceVolumeResolver.DEFAULT_VOLUME)))
                .toList();
        return new DeviceGroupDetail(
                g.getId(),
                g.getProject() != null ? g.getProject().getId() : null,
                g.getProject() != null ? g.getProject().getName() : null,
                g.getName(),
                g.getVolume(),
                devices.size(),
                g.getCreatedAt(),
                devices);
    }
}
