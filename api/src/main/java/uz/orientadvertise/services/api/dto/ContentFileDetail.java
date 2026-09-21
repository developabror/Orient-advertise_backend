package uz.orientadvertise.services.api.dto;

import java.time.Instant;

import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.service.ContentListService.ContentFileView;

/**
 * Detail projection for {@code GET /api/content/{id}}. Superset of {@link ContentFileSummary}
 * with the soft-delete timestamp so admin tooling can audit a row the listing endpoint hides.
 *
 * <p><b>Storage internals are intentionally NOT exposed</b> ({@code storageKey},
 * {@code processedStorageKey}, {@code thumbnailStorageKey}, {@code checksum}) — they let a
 * role-visible caller enumerate/substitute objects in the bucket. Internal callers that need
 * a key read the {@code ContentFile} entity directly; the wire DTO carries only the
 * presigned {@code thumbnailUrl}.
 *
 * <p>{@code thumbnailUrl} / {@code thumbnailExpiresAt} and the {@code invalidReason} /
 * {@code transcodeLastError} split carry the same semantics as the listing projection — see
 * {@link ContentFileSummary}.
 */
public record ContentFileDetail(
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
        boolean canManage,
        Instant deletedAt
) {
    /** See {@link ContentFileSummary#from} for the caller-flag semantics. */
    public static ContentFileDetail from(ContentFileView view,
                                         String callerUsername,
                                         boolean callerCanManageAll,
                                         boolean callerIsOperatorOnly) {
        ContentFile file = view.file();
        boolean canManage = callerCanManageAll
                || (callerIsOperatorOnly && callerUsername != null
                        && callerUsername.equals(file.getUploadedBy()));
        return new ContentFileDetail(
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
                canManage,
                file.getDeletedAt());
    }
}
