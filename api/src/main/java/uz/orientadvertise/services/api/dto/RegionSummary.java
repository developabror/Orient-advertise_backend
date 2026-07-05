package uz.orientadvertise.services.api.dto;

import java.time.Instant;

import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.service.RegionManagementService.RegionView;

/**
 * Listing projection for {@code GET /api/regions}. {@code facilityCount} and
 * {@code deviceCount} are computed by {@link uz.orientadvertise.services.service.RegionManagementService}
 * from {@code FacilityRepository.countByRegionId} and
 * {@code DeviceRepository.countByRegionIdAndDeletedAtIsNull} respectively — soft-deleted
 * devices are excluded from the device count.
 */
public record RegionSummary(
        Long id,
        Long projectId,
        String code,
        String name,
        long facilityCount,
        long deviceCount,
        Instant createdAt
) {
    public static RegionSummary from(RegionView view) {
        Region r = view.region();
        return new RegionSummary(
                r.getId(),
                r.getProject() != null ? r.getProject().getId() : null,
                r.getCode(),
                r.getName(),
                view.facilityCount(),
                view.deviceCount(),
                r.getCreatedAt());
    }
}
