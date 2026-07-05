package uz.orientadvertise.services.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.OperatorContentAccess;

public interface OperatorContentAccessRepository extends JpaRepository<OperatorContentAccess, Long> {

    List<OperatorContentAccess> findByUserId(Long userId);

    @Query("SELECT oca.contentFile FROM OperatorContentAccess oca " +
           "WHERE oca.user.id = :userId AND oca.contentFile.deletedAt IS NULL")
    List<ContentFile> findAccessibleContent(@Param("userId") Long userId);

    /**
     * Id-only projection used by the {@code GET /api/content} listing for operator scoping:
     * the result feeds an {@code id IN (...)} filter on the content query. Soft-deleted files
     * are excluded so an already-granted-then-deleted file cannot leak back into the listing —
     * this {@code deletedAt IS NULL} clause is correctness-bearing.
     */
    @Query("SELECT oca.contentFile.id FROM OperatorContentAccess oca " +
           "WHERE oca.user.id = :userId AND oca.contentFile.deletedAt IS NULL")
    List<Long> findContentIdsByUserId(@Param("userId") Long userId);

    boolean existsByUserIdAndContentFileId(Long userId, Long contentFileId);

    Optional<OperatorContentAccess> findByUserIdAndContentFileId(Long userId, Long contentFileId);

    @Modifying
    @Query("DELETE FROM OperatorContentAccess oca WHERE oca.user.id = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
