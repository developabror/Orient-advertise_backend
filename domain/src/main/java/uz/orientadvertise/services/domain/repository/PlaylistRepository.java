package uz.orientadvertise.services.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.Playlist;

public interface PlaylistRepository extends JpaRepository<Playlist, Long> {

    List<Playlist> findByProjectIdAndDeletedAtIsNull(Long projectId);

    Optional<Playlist> findByIdAndDeletedAtIsNull(Long id);

    /**
     * Filtered, paginated lookup for {@code GET /api/playlists}. Soft-deleted rows
     * are excluded; both filters are optional. The {@code name} match is case-insensitive
     * substring — admins typically search a partial name.
     */
    @Query("SELECT p FROM Playlist p " +
           "WHERE p.deletedAt IS NULL " +
           "AND (:projectId IS NULL OR p.project.id = :projectId) " +
           "AND (CAST(:name AS string) IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%'))) " +
           "AND (:projectIds IS NULL OR p.project.id IN :projectIds)")
    Page<Playlist> findFiltered(@Param("projectId") Long projectId,
                                  @Param("name") String name,
                                  @Param("projectIds") Collection<Long> projectIds,
                                  Pageable pageable);

    /**
     * App-level pre-check for the {@code UNIQUE (project_id, name)} DB constraint —
     * used by create/rename so the API surfaces a clean 409 before letting the DB
     * raise a {@code DataIntegrityViolationException}. Case-sensitive to match the
     * DB constraint's behavior exactly.
     */
    boolean existsByProjectIdAndNameAndDeletedAtIsNull(Long projectId, String name);

    /**
     * Same duplicate check as above but excludes the row being renamed — otherwise
     * a no-op rename ("X" → "X") would falsely trip the duplicate guard against itself.
     */
    @Query("SELECT COUNT(p) > 0 FROM Playlist p " +
           "WHERE p.project.id = :projectId " +
           "AND p.name = :name " +
           "AND p.id <> :excludeId " +
           "AND p.deletedAt IS NULL")
    boolean existsDuplicateExcluding(@Param("projectId") Long projectId,
                                       @Param("name") String name,
                                       @Param("excludeId") Long excludeId);
}
