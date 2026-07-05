package uz.orientadvertise.services.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.ContentFile;

public interface ContentFileRepository extends JpaRepository<ContentFile, Long> {

    List<ContentFile> findByProjectIdAndDeletedAtIsNull(Long projectId);

    Optional<ContentFile> findByIdAndDeletedAtIsNull(Long id);

    List<ContentFile> findByStatusAndDeletedAtIsNull(ContentFile.Status status);

    /**
     * Legacy content files needing metadata reconciliation: READY with a processed object but no
     * {@code checksum} yet (transcoded before checksums were recorded). The startup reconciler
     * backfills their {@code checksum} + true {@code sizeBytes} from the stored object. Files
     * processed by the current pipeline already carry a checksum, so they're naturally excluded.
     */
    List<ContentFile> findByStatusAndChecksumIsNullAndProcessedStorageKeyIsNotNullAndDeletedAtIsNull(
            ContentFile.Status status);

    /**
     * Filtered, paginated content listing for {@code GET /api/content}. Soft-deleted files
     * are always excluded. Each filter parameter is optional — pass {@code null} to skip
     * that predicate. {@code name} is matched case-insensitively as a substring.
     *
     * <p>The split between this method and {@link #findFilteredScoped} keeps the JPQL
     * straightforward — a single {@code IN :ids} clause that switches on/off via a
     * boolean would force an empty-list bind on the unscoped path, which Hibernate
     * rejects on some versions.
     */
    @Query("SELECT cf FROM ContentFile cf " +
           "WHERE cf.deletedAt IS NULL " +
           "AND (:projectId IS NULL OR cf.project.id = :projectId) " +
           "AND (:status IS NULL OR cf.status = :status) " +
           "AND (CAST(:name AS string) IS NULL OR LOWER(cf.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))")
    Page<ContentFile> findFiltered(@Param("projectId") Long projectId,
                                    @Param("status") ContentFile.Status status,
                                    @Param("name") String name,
                                    Pageable pageable);

    /**
     * Same filters as {@link #findFiltered} but additionally constrained to {@code ids}.
     * Used by the ADVERTISER role to limit the listing to content files the advertiser is
     * linked to via {@code advertiser_content_access}. {@code ids} must be non-empty —
     * the caller short-circuits to {@code Page.empty()} when the advertiser has no grants.
     */
    @Query("SELECT cf FROM ContentFile cf " +
           "WHERE cf.deletedAt IS NULL " +
           "AND cf.id IN :ids " +
           "AND (:projectId IS NULL OR cf.project.id = :projectId) " +
           "AND (:status IS NULL OR cf.status = :status) " +
           "AND (CAST(:name AS string) IS NULL OR LOWER(cf.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))")
    Page<ContentFile> findFilteredScoped(@Param("projectId") Long projectId,
                                          @Param("status") ContentFile.Status status,
                                          @Param("name") String name,
                                          @Param("ids") Collection<Long> ids,
                                          Pageable pageable);

    /**
     * Ids of non-deleted content uploaded by the given username — the ownership half of an
     * operator's visible content set ({@code owned ∪ granted}). Soft-deleted rows are excluded.
     */
    @Query("SELECT cf.id FROM ContentFile cf WHERE cf.uploadedBy = :username AND cf.deletedAt IS NULL")
    List<Long> findIdsByUploadedBy(@Param("username") String username);
}
