package uz.orientadvertise.services.api.dto;

import java.time.Instant;

import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.service.ContentListService.ContentFileView;

/**
 * Listing projection for {@code GET /api/content}. A deliberately narrow subset of
 * {@link ContentFile} — internal storage keys, checksums, and the soft-delete timestamp
 * stay server-side.
 *
 * <p>{@code invalidReason} carries the terminal explanation for an {@code INVALID} row;
 * {@code transcodeLastError} carries it for a {@code FAILED} one. They are separate columns and
 * exactly one is ever populated, so a card renders whichever is non-null. Before v1.0.134 the
 * FAILED reason was written to the database and exposed nowhere, so a failed card could not say
 * why it failed.
 *
 * <p>{@code thumbnailUrl} / {@code thumbnailExpiresAt} are populated for {@code READY}
 * rows that have a stored thumbnail; both are null for transcoding rows, failed rows,
 * and rows where the best-effort poster step did not produce a thumbnail. The 15-minute
 * TTL is fixed by {@code ContentListService.THUMBNAIL_URL_EXPIRY_MINUTES}; FE callers
 * should refresh the listing rather than rely on URLs beyond the {@code expiresAt}.
 */
public record ContentFileSummary(
        Long id,
        Long projectId,
        String name,
        String contentType,
        Long sizeBytes,
        Integer durationSeconds,
        String status,
        String invalidReason,
        String transcodeLastError,
        String thumbnailUrl,
        Instant thumbnailExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        String uploadedByUsername,
        boolean canManage
) {
    /**
     * @param view              the listing projection (file + thumbnail)
     * @param callerUsername    the current caller's username (for the owned check)
     * @param callerCanManageAll true when the caller is ADMIN (or admin+operator hybrid) — manages every row
     * @param callerIsOperatorOnly true when the caller's sole role is OPERATOR — manages only owned rows
     */
    public static ContentFileSummary from(ContentFileView view,
                                          String callerUsername,
                                          boolean callerCanManageAll,
                                          boolean callerIsOperatorOnly) {
        ContentFile file = view.file();
        boolean canManage = callerCanManageAll
                || (callerIsOperatorOnly && callerUsername != null
                        && callerUsername.equals(file.getUploadedBy()));
        return new ContentFileSummary(
                file.getId(),
                file.getProject() != null ? file.getProject().getId() : null,
                file.getName(),
                file.getContentType(),
                file.getSizeBytes(),
                file.getDurationSeconds(),
                file.getStatus() != null ? file.getStatus().name() : null,
                file.getInvalidReason(),
                file.getTranscodeLastError(),
                view.thumbnailUrl(),
                view.thumbnailExpiresAt(),
                file.getCreatedAt(),
                file.getUpdatedAt(),
                file.getUploadedBy(),
                canManage);
    }
}
