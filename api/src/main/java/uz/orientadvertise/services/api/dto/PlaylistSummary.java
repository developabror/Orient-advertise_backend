package uz.orientadvertise.services.api.dto;

import java.time.Instant;

import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.service.PlaylistManagementService.PlaylistStats;
import uz.orientadvertise.services.service.PlaylistManagementService.PlaylistView;

/**
 * Listing projection for {@code GET /api/playlists}. {@code itemCount} and
 * {@code totalDurationSeconds} come from a batched aggregate query in
 * {@link uz.orientadvertise.services.service.PlaylistManagementService} so the listing
 * never fans out to N+1 lazy-loads of {@code playlist.items}.
 */
public record PlaylistSummary(
        Long id,
        Long projectId,
        String name,
        long itemCount,
        long totalDurationSeconds,
        Instant createdAt,
        Instant updatedAt
) {
    public static PlaylistSummary from(PlaylistView view) {
        Playlist p = view.playlist();
        PlaylistStats stats = view.stats();
        return new PlaylistSummary(
                p.getId(),
                p.getProject() != null ? p.getProject().getId() : null,
                p.getName(),
                stats.itemCount(),
                stats.totalDurationSeconds(),
                p.getCreatedAt(),
                p.getUpdatedAt());
    }
}
