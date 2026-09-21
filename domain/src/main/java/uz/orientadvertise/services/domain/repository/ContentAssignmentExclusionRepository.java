package uz.orientadvertise.services.domain.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.ContentAssignmentExclusion;

public interface ContentAssignmentExclusionRepository extends JpaRepository<ContentAssignmentExclusion, Long> {

    List<ContentAssignmentExclusion> findByAssignmentId(Long assignmentId);

    /**
     * Excluded device ids for an assignment, as a flat id projection. Used by the device-aware
     * overlap check to compute an assignment's effective device set without initializing the lazy
     * {@code device}/{@code assignment} proxies or selecting full {@code Device} rows.
     */
    @Query("SELECT e.device.id FROM ContentAssignmentExclusion e WHERE e.assignment.id = :assignmentId")
    List<Long> findDeviceIdsByAssignmentId(@Param("assignmentId") Long assignmentId);

    boolean existsByAssignmentIdAndDeviceId(Long assignmentId, Long deviceId);

    List<ContentAssignmentExclusion> findByDeviceId(Long deviceId);

    /**
     * Delete every exclusion carrying one exact reason string. Used by
     * {@code ContentAssignmentService.softDelete} to undo a partial supersede: the narrowing rows a
     * REPLACE confirm wrote are stamped with {@code ContentAssignmentService.partialSupersedeReason}
     * (which embeds the superseding assignment's id), so cancelling that assignment can delete
     * exactly the rows it caused — and no others, including hand-written operator exclusions on the
     * same predecessor.
     *
     * <p>Exclusions have no FK to the assignment that <i>caused</i> them (only to the one they
     * narrow), so the reason is the join key. That is why the string has a single formatter.
     *
     * <p>Deliberately NOT {@code clearAutomatically}/{@code flushAutomatically}: the caller has a
     * dirty {@code ContentAssignment} in the persistence context (it has just been soft-deleted) and
     * clearing would detach it, losing the cancel. The delete touches a different table, so
     * Hibernate does not auto-flush the assignment either — it still flushes at commit.
     */
    @Modifying
    @Query("DELETE FROM ContentAssignmentExclusion e WHERE e.reason = :reason")
    int deleteByReason(@Param("reason") String reason);
}
