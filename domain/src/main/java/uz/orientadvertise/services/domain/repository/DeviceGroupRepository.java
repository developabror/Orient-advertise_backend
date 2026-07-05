package uz.orientadvertise.services.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.DeviceGroup;

public interface DeviceGroupRepository extends JpaRepository<DeviceGroup, Long> {

    List<DeviceGroup> findByProjectIdAndDeletedAtIsNull(Long projectId);

    Optional<DeviceGroup> findByIdAndDeletedAtIsNull(Long id);

    /**
     * Same as {@link #findByIdAndDeletedAtIsNull} but eagerly fetches the parent project.
     * The detail endpoint maps to {@code DeviceGroupDetail} after the service transaction
     * has closed, and that DTO reads {@code project.getName()} — without the JOIN FETCH
     * the lazy proxy throws {@code LazyInitializationException} under
     * {@code spring.jpa.open-in-view: false}.
     */
    @Query("SELECT g FROM DeviceGroup g LEFT JOIN FETCH g.project " +
           "WHERE g.id = :id AND g.deletedAt IS NULL")
    Optional<DeviceGroup> findByIdAndDeletedAtIsNullWithProject(@Param("id") Long id);

    boolean existsByProjectIdAndNameAndDeletedAtIsNull(Long projectId, String name);

    /**
     * Filtered, paginated lookup for {@code GET /api/device-groups}. Soft-deleted rows
     * are excluded; both filters are optional. Name is matched case-insensitively as a
     * substring — admins typically search a partial name.
     *
     * <p>{@code LEFT JOIN FETCH g.project} is required: the listing projection
     * ({@code DeviceGroupSummary}) reads {@code project.getName()}, and with
     * {@code open-in-view: false} the lazy {@link DeviceGroup#project} proxy would
     * otherwise throw {@code LazyInitializationException} when the controller assembles
     * the DTO after the service transaction has closed. Single-valued association →
     * safe with pagination; Spring Data generates the count query separately.
     */
    @Query("SELECT g FROM DeviceGroup g " +
           "LEFT JOIN FETCH g.project " +
           "WHERE g.deletedAt IS NULL " +
           "AND (:projectId IS NULL OR g.project.id = :projectId) " +
           "AND (CAST(:name AS string) IS NULL OR LOWER(g.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%'))) " +
           "AND (:projectIds IS NULL OR g.project.id IN :projectIds)")
    Page<DeviceGroup> findFiltered(@Param("projectId") Long projectId,
                                     @Param("name") String name,
                                     @Param("projectIds") Collection<Long> projectIds,
                                     Pageable pageable);

    /**
     * Duplicate check that excludes the row being renamed. Without the exclusion a no-op
     * rename ("X" → "X") would falsely trip against itself.
     */
    @Query("SELECT COUNT(g) > 0 FROM DeviceGroup g " +
           "WHERE g.project.id = :projectId " +
           "AND g.name = :name " +
           "AND g.id <> :excludeId " +
           "AND g.deletedAt IS NULL")
    boolean existsDuplicateExcluding(@Param("projectId") Long projectId,
                                       @Param("name") String name,
                                       @Param("excludeId") Long excludeId);
}
