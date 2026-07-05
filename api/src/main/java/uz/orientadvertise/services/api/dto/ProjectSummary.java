package uz.orientadvertise.services.api.dto;

import java.time.Instant;

import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.service.ProjectManagementService.ProjectView;

/**
 * Listing projection for {@code GET /api/projects}. {@code regionCount} comes from a
 * single batched aggregate keyed across the full project set — see
 * {@link uz.orientadvertise.services.domain.repository.RegionRepository#countRegionsPerProject}.
 *
 * <p>Projects are unpaginated by design (small set), so the list response is a flat
 * {@code List<ProjectSummary>}, not a {@code Page<...>}.
 */
public record ProjectSummary(
        Long id,
        String name,
        long regionCount,
        Instant createdAt
) {
    public static ProjectSummary from(ProjectView view) {
        Project p = view.project();
        return new ProjectSummary(p.getId(), p.getName(), view.regionCount(), p.getCreatedAt());
    }
}
