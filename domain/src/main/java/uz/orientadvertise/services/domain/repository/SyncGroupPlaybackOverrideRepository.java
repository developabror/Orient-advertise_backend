package uz.orientadvertise.services.domain.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.orientadvertise.services.domain.model.SyncGroupPlaybackOverride;

/**
 * The (at most one) active playback re-anchor per sync group. Looked up on every {@code /sync} for a
 * grouped device and upserted on each operator jump; the stale row is removed at {@code /sync} time
 * when the group's content/version has moved on (best-effort cleanup).
 */
public interface SyncGroupPlaybackOverrideRepository extends JpaRepository<SyncGroupPlaybackOverride, Long> {

    Optional<SyncGroupPlaybackOverride> findBySyncGroupId(Long syncGroupId);

    void deleteBySyncGroupId(Long syncGroupId);
}
