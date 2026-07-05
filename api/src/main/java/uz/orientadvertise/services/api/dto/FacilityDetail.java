package uz.orientadvertise.services.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceVolumeResolver;
import uz.orientadvertise.services.domain.model.Facility;
import uz.orientadvertise.services.service.FacilityManagementService.FacilityDetailView;

/**
 * Detail projection for {@code GET /api/facilities/{id}} and the responses of create /
 * rename. Strict superset of {@link FacilitySummary}: includes {@code address} (which
 * the listing omits to keep rows compact) and the active member device list as
 * {@link DeviceSummary} entries.
 */
public record FacilityDetail(
        Long id,
        Long regionId,
        String regionName,
        String name,
        String address,
        long deviceCount,
        Instant createdAt,
        List<DeviceSummary> devices
) {
    public static FacilityDetail from(FacilityDetailView view,
                                      Map<Long, Device.Status> computedStatuses,
                                      Map<Long, Integer> effectiveVolumes) {
        Facility f = view.facility();
        var devices = view.devices().stream()
                .map(d -> DeviceSummary.from(d, computedStatuses.get(d.getId()),
                        effectiveVolumes.getOrDefault(d.getId(), DeviceVolumeResolver.DEFAULT_VOLUME)))
                .toList();
        return new FacilityDetail(
                f.getId(),
                f.getRegion() != null ? f.getRegion().getId() : null,
                f.getRegion() != null ? f.getRegion().getName() : null,
                f.getName(),
                f.getAddress(),
                devices.size(),
                f.getCreatedAt(),
                devices);
    }
}
