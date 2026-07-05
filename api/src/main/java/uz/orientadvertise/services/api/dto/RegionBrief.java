package uz.orientadvertise.services.api.dto;

import java.time.Instant;

import uz.orientadvertise.services.domain.model.Region;

/**
 * Slim region projection embedded in {@link ProjectDetail}. Carries the public id,
 * code, name, and createdAt — the per-region facility/device counts are available via
 * the dedicated {@link RegionSummary} when the operator drills into the regions
 * listing.
 *
 * <p>Distinct from {@link RegionSummary} (which adds {@code projectId}/{@code facilityCount}/
 * {@code deviceCount}). Inside a {@link ProjectDetail}, {@code projectId} would be
 * redundant (inherited from the parent) and the counts would each require an extra
 * per-row query — this projection avoids both.
 */
public record RegionBrief(
        Long id,
        String code,
        String name,
        Instant createdAt
) {
    public static RegionBrief from(Region region) {
        return new RegionBrief(region.getId(), region.getCode(), region.getName(),
                region.getCreatedAt());
    }
}
