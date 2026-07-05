package uz.orientadvertise.services.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.Facility;

public interface FacilityRepository extends JpaRepository<Facility, Long> {

    List<Facility> findByRegionId(Long regionId);

    /**
     * By-id lookup that eagerly fetches the parent region. The detail endpoint maps to
     * {@code FacilityDetail} after the service transaction has closed, and that DTO
     * reads {@code region.getName()} — without the JOIN FETCH the lazy proxy throws
     * {@code LazyInitializationException} under {@code spring.jpa.open-in-view: false}.
     */
    @Query("SELECT f FROM Facility f LEFT JOIN FETCH f.region WHERE f.id = :id")
    Optional<Facility> findByIdWithRegion(@Param("id") Long id);

    Optional<Facility> findByRegionIdAndName(Long regionId, String name);

    boolean existsByRegionIdAndName(Long regionId, String name);

    long countByRegionId(Long regionId);

    /**
     * Filtered, paginated lookup for {@code GET /api/facilities}. Facilities are not
     * soft-deleted (per the org-tree contract — only devices and device groups carry
     * {@code deletedAt}), so no soft-delete clause here. Both filters are optional.
     *
     * <p>{@code LEFT JOIN FETCH f.region} is required: the listing projection
     * ({@code FacilitySummary}) reads {@code region.getName()}, and with
     * {@code open-in-view: false} the lazy {@link Facility#region} proxy would otherwise
     * throw {@code LazyInitializationException} when the controller assembles the DTO
     * after the service transaction has closed. Single-valued association → safe with
     * pagination; Spring Data generates the count query separately.
     */
    @Query("SELECT f FROM Facility f " +
           "LEFT JOIN FETCH f.region " +
           "WHERE (:regionId IS NULL OR f.region.id = :regionId) " +
           "AND (CAST(:name AS string) IS NULL OR LOWER(f.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%'))) " +
           "AND (:projectIds IS NULL OR f.region.project.id IN :projectIds)")
    Page<Facility> findFiltered(@Param("regionId") Long regionId,
                                  @Param("name") String name,
                                  @Param("projectIds") Collection<Long> projectIds,
                                  Pageable pageable);

    /**
     * Duplicate check that excludes the row being renamed — without the exclusion a
     * no-op rename ("X" → "X") would falsely trip against itself.
     */
    @Query("SELECT COUNT(f) > 0 FROM Facility f " +
           "WHERE f.region.id = :regionId " +
           "AND f.name = :name " +
           "AND f.id <> :excludeId")
    boolean existsDuplicateExcluding(@Param("regionId") Long regionId,
                                       @Param("name") String name,
                                       @Param("excludeId") Long excludeId);
}
