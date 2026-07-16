package uz.orientadvertise.services.domain.repository;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.SyncGroup;

/**
 * Persistence for {@link SyncGroup}. Sync groups are <b>hard-deleted</b>, so there is no
 * {@code deletedAt} column and none of these methods carry an {@code AndDeletedAtIsNull}
 * filter — the {@code UNIQUE (project_id, name)} constraint is exact.
 */
public interface SyncGroupRepository extends JpaRepository<SyncGroup, Long> {

    /**
     * Load a group with its parent project eagerly fetched. The detail / create / rename
     * responses map to {@code SyncGroupDetail} after the service transaction has closed, and
     * that DTO reads {@code project.getName()} — without the JOIN FETCH the lazy
     * {@link SyncGroup#getProject()} proxy throws {@code LazyInitializationException} under
     * {@code spring.jpa.open-in-view: false}.
     */
    @Query("SELECT g FROM SyncGroup g LEFT JOIN FETCH g.project WHERE g.id = :id")
    Optional<SyncGroup> findByIdWithProject(@Param("id") Long id);

    boolean existsByProjectIdAndName(Long projectId, String name);

    /**
     * Filtered, paginated lookup for {@code GET /api/sync-groups}. Both filters are optional;
     * name is matched case-insensitively as a substring. {@code LEFT JOIN FETCH g.project} is
     * required — the {@code SyncGroupSummary} projection reads {@code project.getName()} after
     * the service transaction closes ({@code open-in-view: false}). Single-valued association
     * → safe with pagination; Spring Data generates the count query separately.
     */
    @Query("SELECT g FROM SyncGroup g " +
           "LEFT JOIN FETCH g.project " +
           "WHERE (:projectId IS NULL OR g.project.id = :projectId) " +
           "AND (CAST(:name AS string) IS NULL OR LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%'))) " +
           "AND (:projectIds IS NULL OR g.project.id IN :projectIds)")
    Page<SyncGroup> findFiltered(@Param("projectId") Long projectId,
                                 @Param("name") String name,
                                 @Param("projectIds") Collection<Long> projectIds,
                                 Pageable pageable);

    /**
     * Duplicate check that excludes the row being renamed — without the exclusion a no-op
     * rename ("X" → "X") would falsely trip against itself.
     */
    @Query("SELECT COUNT(g) > 0 FROM SyncGroup g " +
           "WHERE g.project.id = :projectId " +
           "AND g.name = :name " +
           "AND g.id <> :excludeId")
    boolean existsDuplicateExcluding(@Param("projectId") Long projectId,
                                     @Param("name") String name,
                                     @Param("excludeId") Long excludeId);
}
