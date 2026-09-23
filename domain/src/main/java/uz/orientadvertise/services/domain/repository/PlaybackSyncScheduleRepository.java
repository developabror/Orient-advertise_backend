package uz.orientadvertise.services.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.PlaybackSyncSchedule;

public interface PlaybackSyncScheduleRepository extends JpaRepository<PlaybackSyncSchedule, Long> {

    /**
     * The single anchor row for an assignment's CONTENT version (V53), or empty until first
     * activation. Keyed on the content hash, not {@code version_number}: an edit yields a new hash
     * and therefore a new anchor, which is what makes a whole group cut over together (VG-06).
     */
    Optional<PlaybackSyncSchedule> findByAssignmentIdAndContentVersion(Long assignmentId, String contentVersion);

    /** Whether this assignment has been anchored at all — i.e. its devices play to a shared clock. */
    boolean existsByAssignmentId(Long assignmentId);

    /**
     * Anchor a content-version if nobody has yet, in the CALLER's transaction. Returns 1 on insert
     * and 0 when another caller won the race — never an exception, so a lost race cannot poison a
     * {@code /sync} transaction (the pattern and its rationale mirror
     * {@code PlaybackLogRepository.insertIgnoringDuplicate}; the caller then re-reads the winner's
     * row). It replaces a {@code REQUIRES_NEW} writer bean, which needed a SECOND pooled connection
     * while {@code /sync} held the first — the deadlock half of VG-07.
     *
     * <p>The conflict target is omitted because H2 in PostgreSQL mode accepts only the bare form.
     * That is exact here: this table has the identity PK (a generated value cannot collide) and
     * {@code uq_playback_sched_cv}. Adding a third unique constraint would make this swallow that
     * conflict too — name the target then, and drop H2 for the test.
     */
    @Modifying
    @Query(value = """
            INSERT INTO playback_sync_schedule
                (assignment_id, version_number, content_version, anchor_epoch_ms, activate_at, created_at)
            VALUES (:assignmentId, :versionNumber, :contentVersion, :anchorEpochMs, :activateAt, :createdAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("assignmentId") Long assignmentId,
                       @Param("versionNumber") int versionNumber,
                       @Param("contentVersion") String contentVersion,
                       @Param("anchorEpochMs") long anchorEpochMs,
                       @Param("activateAt") Instant activateAt,
                       @Param("createdAt") Instant createdAt);

    /**
     * Recent + upcoming cut-overs for the readiness poll. Since {@code activateAt = createdAt + minLead}
     * (a small fixed lead), passing {@code now - maxLeadCap} bounds the scan to roughly the last cut-over
     * window rather than every schedule ever written.
     */
    List<PlaybackSyncSchedule> findByActivateAtAfter(Instant threshold);
}
