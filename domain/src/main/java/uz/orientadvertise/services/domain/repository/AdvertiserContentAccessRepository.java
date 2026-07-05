package uz.orientadvertise.services.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.AdvertiserContentAccess;
import uz.orientadvertise.services.domain.model.ContentFile;

public interface AdvertiserContentAccessRepository extends JpaRepository<AdvertiserContentAccess, Long> {

    List<AdvertiserContentAccess> findByUserId(Long userId);

    @Query("SELECT a.contentFile FROM AdvertiserContentAccess a " +
           "WHERE a.user.id = :userId AND a.contentFile.deletedAt IS NULL")
    List<ContentFile> findAccessibleContent(@Param("userId") Long userId);

    /**
     * Id-only projection used by the {@code GET /api/content} listing for advertiser
     * scoping: the result feeds an {@code id IN (...)} filter on the content query, so a
     * content-row projection ({@link #findAccessibleContent}) would be wasted I/O. Soft-
     * deleted files are excluded so they cannot leak into the listing even if a stale grant
     * still references them.
     */
    @Query("SELECT a.contentFile.id FROM AdvertiserContentAccess a " +
           "WHERE a.user.id = :userId AND a.contentFile.deletedAt IS NULL")
    List<Long> findContentIdsByUserId(@Param("userId") Long userId);

    boolean existsByUserIdAndContentFileId(Long userId, Long contentFileId);

    Optional<AdvertiserContentAccess> findByUserIdAndContentFileId(Long userId, Long contentFileId);

    @Modifying
    @Query("DELETE FROM AdvertiserContentAccess a WHERE a.user.id = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
