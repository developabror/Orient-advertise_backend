package uz.orientadvertise.services.api.dto;

import uz.orientadvertise.services.domain.model.DeviceGroup;

/**
 * Slim device-group projection embedded in {@link ProjectDetail}. Carries only the public
 * id and name — the device count and full member list are available via the dedicated
 * {@code GET /api/device-groups/{id}} endpoint when an operator drills in.
 *
 * <p>Distinct from {@link DeviceGroupSummary} which surfaces in the standalone group
 * listing and additionally carries {@code projectId}/{@code projectName}/{@code deviceCount}.
 * Inside a project detail, {@code projectId}/{@code projectName} would be redundant and
 * {@code deviceCount} would require a per-row query — this projection avoids both.
 */
public record DeviceGroupBrief(
        Long id,
        String name
) {
    public static DeviceGroupBrief from(DeviceGroup group) {
        return new DeviceGroupBrief(group.getId(), group.getName());
    }
}
