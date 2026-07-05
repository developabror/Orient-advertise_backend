package uz.orientadvertise.services.api.dto;

import java.time.Instant;
import java.util.List;

import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.service.RegionManagementService.RegionDetailView;

/**
 * Detail projection for {@code GET /api/regions/{id}} and the responses of create /
 * update. Strict superset of {@link RegionSummary}: includes the region's facility list,
 * projected to public-field-only DTOs. Device groups are no longer region-scoped (they
 * belong to the project, V37) and are surfaced on {@link ProjectDetail} instead.
 */
public record RegionDetail(
        Long id,
        Long projectId,
        String code,
        String name,
        long facilityCount,
        long deviceCount,
        Instant createdAt,
        List<FacilityBrief> facilities
) {
    public static RegionDetail from(RegionDetailView view) {
        Region r = view.region();
        return new RegionDetail(
                r.getId(),
                r.getProject() != null ? r.getProject().getId() : null,
                r.getCode(),
                r.getName(),
                view.facilityCount(),
                view.deviceCount(),
                r.getCreatedAt(),
                view.facilities().stream().map(FacilityBrief::from).toList());
    }
}
