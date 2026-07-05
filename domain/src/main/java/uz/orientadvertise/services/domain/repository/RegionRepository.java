package uz.orientadvertise.services.domain.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.Region;

public interface RegionRepository extends JpaRepository<Region, Long> {

    List<Region> findByProjectId(Long projectId);

    /** Drives both the project-detail's {@code regionCount} and the delete guard. */
    long countByProjectId(Long projectId);

    /**
     * Batched aggregate for {@code GET /api/projects} — returns one
     * {@code (projectId, count)} tuple per project that has at least one region. The
     * service zero-fills missing projects so the response always reports a count.
     * Single round trip avoids N+1 across the full project list.
     */
    @Query("SELECT r.project.id, COUNT(r) FROM Region r GROUP BY r.project.id")
    List<Object[]> countRegionsPerProject();

    /**
     * Filtered, paginated lookup for {@code GET /api/regions}. Region rows are not
     * soft-deleted (per the org-tree contract — only devices carry {@code deletedAt}),
     * so there is no {@code deletedAt IS NULL} clause here. Both filters are optional.
     */
    @Query("SELECT r FROM Region r " +
           "WHERE (:projectId IS NULL OR r.project.id = :projectId) " +
           "AND (CAST(:name AS string) IS NULL OR LOWER(r.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%'))) " +
           "AND (:projectIds IS NULL OR r.project.id IN :projectIds)")
    Page<Region> findFiltered(@Param("projectId") Long projectId,
                                @Param("name") String name,
                                @Param("projectIds") Collection<Long> projectIds,
                                Pageable pageable);

    /**
     * Drives the create-time duplicate guard. Matches the DB-level
     * {@code uq_region_code_per_project} constraint exactly.
     */
    boolean existsByProjectIdAndCode(Long projectId, String code);

    /**
     * Same duplicate check, but excludes the row being recoded — without the exclusion
     * a no-op recode ("X" → "X") would falsely trip the guard against itself.
     */
    @Query("SELECT COUNT(r) > 0 FROM Region r " +
           "WHERE r.project.id = :projectId " +
           "AND r.code = :code " +
           "AND r.id <> :excludeId")
    boolean existsDuplicateCodeExcluding(@Param("projectId") Long projectId,
                                            @Param("code") String code,
                                            @Param("excludeId") Long excludeId);
}
