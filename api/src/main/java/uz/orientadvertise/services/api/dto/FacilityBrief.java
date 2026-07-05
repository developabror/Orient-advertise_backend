package uz.orientadvertise.services.api.dto;

import uz.orientadvertise.services.domain.model.Facility;

/**
 * Compact facility projection embedded in {@link RegionDetail}. Carries the public
 * fields only ({@code id}, {@code name}, {@code address}); the dedicated
 * {@code GET /api/facilities/{id}} endpoint exposes the full surface (with member
 * device list) when the operator drills in.
 *
 * <p>Distinct from {@link FacilitySummary}, which surfaces in the standalone facility
 * listing and additionally carries {@code regionId}/{@code regionName}/{@code deviceCount}.
 * Inside a {@link RegionDetail}, {@code regionId}/{@code regionName} would be redundant
 * (they're inherited from the parent region) and {@code deviceCount} would require a
 * per-row query — this projection avoids both.
 */
public record FacilityBrief(
        Long id,
        String name,
        String address
) {
    public static FacilityBrief from(Facility facility) {
        return new FacilityBrief(facility.getId(), facility.getName(), facility.getAddress());
    }
}
