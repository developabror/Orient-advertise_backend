package uz.orientadvertise.services.api.dto;

import java.time.Instant;
import java.util.List;

import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.service.ProjectManagementService.ProjectDetailView;

/**
 * Detail projection for {@code GET /api/projects/{id}} and the responses of create /
 * rename. Strict superset of {@link ProjectSummary}: includes the immediate child
 * regions as {@link RegionBrief} entries and the project's device groups as
 * {@link DeviceGroupBrief} entries (groups belong to the project, V37).
 */
public record ProjectDetail(
        Long id,
        String name,
        long regionCount,
        Instant createdAt,
        List<RegionBrief> regions,
        List<DeviceGroupBrief> deviceGroups
) {
    public static ProjectDetail from(ProjectDetailView view) {
        Project p = view.project();
        var regions = view.regions().stream().map(RegionBrief::from).toList();
        var deviceGroups = view.deviceGroups().stream().map(DeviceGroupBrief::from).toList();
        return new ProjectDetail(
                p.getId(), p.getName(), view.regionCount(), p.getCreatedAt(), regions, deviceGroups);
    }
}
