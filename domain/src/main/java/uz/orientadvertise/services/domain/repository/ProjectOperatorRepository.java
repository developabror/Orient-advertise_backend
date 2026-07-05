package uz.orientadvertise.services.domain.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.ProjectOperator;

public interface ProjectOperatorRepository extends JpaRepository<ProjectOperator, Long> {

    /** Project ids an operator is assigned to — the operator's whole-project scope. */
    @Query("SELECT po.project.id FROM ProjectOperator po WHERE po.user.id = :userId")
    List<Long> findProjectIdsByUserId(@Param("userId") Long userId);

    /**
     * Operator assignments for a project. MUST return {@code List<ProjectOperator>} (NOT
     * {@code List<AppUser>}) — the {@code OperatorRef} wire contract needs {@code assignedAt} /
     * {@code assignedBy}, which live on this row, not on {@link uz.orientadvertise.services.domain.model.AppUser}.
     *
     * <p>{@code JOIN FETCH po.user} initializes the LAZY user within the transaction — the
     * controller maps {@code OperatorRef} (reading {@code user.username}) after the session closes,
     * so without the fetch this throws {@code LazyInitializationException}.
     */
    @Query("SELECT po FROM ProjectOperator po JOIN FETCH po.user WHERE po.project.id = :projectId")
    List<ProjectOperator> findOperatorsByProjectId(@Param("projectId") Long projectId);

    boolean existsByUserIdAndProjectId(Long userId, Long projectId);

    Optional<ProjectOperator> findByUserIdAndProjectId(Long userId, Long projectId);

    /**
     * Bulk-remove every assignment for the user. Called when an operator is deleted — the
     * non-cascading user FK would otherwise block the delete, and orphan assignments would
     * survive a future username reuse. Mirrors the advertiser cascade.
     */
    @Modifying
    @Query("DELETE FROM ProjectOperator po WHERE po.user.id = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);
}
