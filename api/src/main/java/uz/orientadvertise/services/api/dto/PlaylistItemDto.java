package uz.orientadvertise.services.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.PlaylistItem;

/**
 * Per-item projection for the playlist detail response.
 *
 * <p>The two duration fields are intentionally distinct:
 * <ul>
 *   <li>{@code durationSeconds} — the source content file's <i>natural</i> duration
 *       from the encoder probe. Always set once the file's status is {@code READY}.</li>
 *   <li>{@code durationOverride} — the playlist-item-specific override (nullable).
 *       Operators set this to truncate or extend playback for a particular slot
 *       without changing the underlying file's metadata.</li>
 * </ul>
 *
 * <p>The effective duration the player uses is {@code durationOverride ?? durationSeconds}.
 * Surfacing both lets the FE render "30s (overridden, file is 45s)".
 */
public record PlaylistItemDto(
        Long id,
        Integer position,
        Long contentFileId,
        String contentFileName,
        Integer durationSeconds,
        @Schema(nullable = true,
                description = "Per-item duration override; null means use the source file's natural duration")
        Integer durationOverride
) {
    public static PlaylistItemDto from(PlaylistItem item) {
        ContentFile cf = item.getContentFile();
        return new PlaylistItemDto(
                item.getId(),
                item.getPosition(),
                cf != null ? cf.getId() : null,
                cf != null ? cf.getName() : null,
                cf != null ? cf.getDurationSeconds() : null,
                item.getDurationSeconds());
    }
}
