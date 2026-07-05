package uz.orientadvertise.services.domain.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.Project;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * Stable, alphabetic order for the unpaginated list endpoint. Projects are a small
     * set in practice (single-digit to low-double-digit), so a deterministic full scan
     * is preferable to any cached / paginated alternative — admins want to see them all
     * at once.
     */
    List<Project> findAllByOrderByNameAsc();

    /**
     * Same ordering as {@link #findAllByOrderByNameAsc} but narrowed to a set of ids — the
     * operator-scoped project list. Only called with a non-empty {@code ids} (the service
     * short-circuits an empty operator scope to an empty list before reaching the repo).
     */
    List<Project> findByIdInOrderByNameAsc(Collection<Long> ids);

    /**
     * App-level pre-check for the {@code uq_project_name} constraint added in V30.
     * Used by create so the API surfaces a clean 409 before the DB raises a
     * {@code DataIntegrityViolationException}.
     */
    boolean existsByName(String name);

    /**
     * Same duplicate check that excludes the row being renamed — without the exclusion
     * a no-op rename ("X" → "X") would falsely trip against itself.
     */
    @Query("SELECT COUNT(p) > 0 FROM Project p WHERE p.name = :name AND p.id <> :excludeId")
    boolean existsDuplicateNameExcluding(@Param("name") String name,
                                           @Param("excludeId") Long excludeId);
}
