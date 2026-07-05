package uz.orientadvertise.services.api.dto;

import java.time.Instant;
import java.util.List;

import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.service.PlaylistManagementService.PlaylistDetailView;

/**
 * Detail projection for {@code GET /api/playlists/{id}} and the responses of create /
 * rename. Strict superset of {@link PlaylistSummary}: includes the full ordered
 * {@link PlaylistItemDto} list. Items are pre-sorted by position at the repository
 * layer.
 */
public record PlaylistDetail(
        Long id,
        Long projectId,
        String name,
        long itemCount,
        long totalDurationSeconds,
        Instant createdAt,
        Instant updatedAt,
        List<PlaylistItemDto> items
) {
    public static PlaylistDetail from(PlaylistDetailView view) {
        Playlist p = view.playlist();
        var items = view.items().stream().map(PlaylistItemDto::from).toList();
        return new PlaylistDetail(
                p.getId(),
                p.getProject() != null ? p.getProject().getId() : null,
                p.getName(),
                items.size(),
                view.totalDurationSeconds(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                items);
    }
}
