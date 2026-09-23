package uz.orientadvertise.services.domain.repository;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.SyncGroupPlaybackOverride;

/**
 * The (at most one) active playback re-anchor per sync group. Looked up on every {@code /sync} for a
 * grouped device and upserted on each operator jump; the stale row is removed at {@code /sync} time
 * when the group's content/version has moved on (best-effort cleanup).
 */
public interface SyncGroupPlaybackOverrideRepository extends JpaRepository<SyncGroupPlaybackOverride, Long> {

    Optional<SyncGroupPlaybackOverride> findBySyncGroupId(Long syncGroupId);

    void deleteBySyncGroupId(Long syncGroupId);

    /**
     * Retire every override written for these assignments, in one statement. Bulk JPQL on purpose:
     * a derived delete loads each row and removes it at flush, so two callers racing on the same row
     * turn a no-op into an optimistic-lock 500. This affects 0 rows and returns 0 instead.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM SyncGroupPlaybackOverride o WHERE o.assignmentId IN :assignmentIds")
    int deleteByAssignmentIdIn(@Param("assignmentIds") Collection<Long> assignmentIds);
}
