package uz.orientadvertise.services.api.dto;

import java.time.Instant;

import uz.orientadvertise.services.domain.model.Facility;
import uz.orientadvertise.services.service.FacilityManagementService.FacilityView;

/**
 * Listing projection for {@code GET /api/facilities}. {@code deviceCount} is the active
 * (non-soft-deleted) device count from {@code DeviceRepository.countByFacilityIdAndDeletedAtIsNull}.
 *
 * <p>Distinct from {@link FacilityBrief}, the slimmer projection embedded inside
 * {@link RegionDetail}: that one omits {@code regionId}/{@code regionName}/{@code deviceCount}
 * because they're either redundant (region inherited from the parent) or expensive to
 * fetch per-row.
 */
public record FacilitySummary(
        Long id,
        Long regionId,
        String regionName,
        String name,
        long deviceCount,
        Instant createdAt
) {
    public static FacilitySummary from(FacilityView view) {
        Facility f = view.facility();
        return new FacilitySummary(
                f.getId(),
                f.getRegion() != null ? f.getRegion().getId() : null,
                f.getRegion() != null ? f.getRegion().getName() : null,
                f.getName(),
                view.deviceCount(),
                f.getCreatedAt());
    }
}
