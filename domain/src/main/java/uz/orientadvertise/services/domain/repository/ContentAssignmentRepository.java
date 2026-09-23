package uz.orientadvertise.services.domain.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.ContentAssignment;

public interface ContentAssignmentRepository extends JpaRepository<ContentAssignment, Long> {

    @Query("SELECT ca FROM ContentAssignment ca " +
           "WHERE ca.targetType = :targetType AND ca.targetId = :targetId " +
           "AND ca.deletedAt IS NULL " +
           "AND ca.status = 'CONFIRMED' " +
           "AND ca.startTime < :end AND ca.endTime > :start")
    List<ContentAssignment> findOverlapping(
            @Param("targetType") ContentAssignment.TargetType targetType,
            @Param("targetId") Long targetId,
            @Param("start") Instant start,
            @Param("end") Instant end);

    @Query("SELECT ca FROM ContentAssignment ca " +
           "WHERE ca.targetType = :targetType AND ca.targetId = :targetId " +
           "AND ca.deletedAt IS NULL " +
           "AND ca.status = 'CONFIRMED' " +
           "AND ca.startTime < :end AND ca.endTime > :start " +
           "AND ca.id <> :excludeId")
    List<ContentAssignment> findOverlappingExcluding(
            @Param("targetType") ContentAssignment.TargetType targetType,
            @Param("targetId") Long targetId,
            @Param("start") Instant start,
            @Param("end") Instant end,
            @Param("excludeId") Long excludeId);

    List<ContentAssignment> findByTargetTypeAndTargetIdAndDeletedAtIsNull(
            ContentAssignment.TargetType targetType, Long targetId);

    /**
     * Every CONFIRMED, non-deleted assignment whose half-open window covers {@code now}, ordered
     * WINNER FIRST by {@link ContentAssignment#PRECEDENCE}: most specific target, then most
     * recently confirmed, then highest id.
     *
     * <p>The recency term is what lets a short campaign override a long-running assignment for its
     * window only and hand it back afterwards (v1.0.142) — both rows stay CONFIRMED and the order
     * decides. {@code COALESCE(confirmedAt, createdAt)} mirrors the SQL views' own COALESCE, so a
     * pre-V48 / never-confirmed row still orders.
     *
     * <p>This ORDER BY is one of FOUR copies of that rule ({@code ContentAssignment.PRECEDENCE},
     * {@code resolveForDevice}, {@code previewForTarget}, {@code device_status_view}). They MUST
     * agree; see {@code ContentAssignmentWindowOverrideTest}, which pins the query and the view
     * against each other.
     */
    @Query("SELECT ca FROM ContentAssignment ca " +
           "WHERE ca.deletedAt IS NULL " +
           "AND ca.status = 'CONFIRMED' " +
           "AND ca.startTime <= :now AND ca.endTime > :now " +
           "ORDER BY ca.priority DESC, COALESCE(ca.confirmedAt, ca.createdAt) DESC, ca.id DESC")
    List<ContentAssignment> findActiveAtTime(@Param("now") Instant now);

    /**
     * Every assignment that could have been driving ONE device during {@code [from, to]} — the
     * question a play reported after the fact asks. Unordered; the caller picks per play with
     * {@link ContentAssignment#wasLiveAt} and {@link ContentAssignment#PRECEDENCE}.
     *
     * <p>Deliberately unlike {@link #findActiveAtTime} in two ways, and both matter:
     * <ul>
     *   <li>it keeps rows <b>deleted after {@code from}</b> — a cancel soft-deletes and a
     *       "Replace" truncates, so filtering {@code deletedAt IS NULL} would erase exactly the
     *       campaign a late-flushed play belongs to;</li>
     *   <li>it matches the <b>window against a range</b>, not an instant.</li>
     * </ul>
     *
     * <p>Targeting is done here rather than in memory so a busy fleet's unrelated campaigns never
     * reach the JVM. A null {@code facilityId} / {@code deviceGroupId} (a device with no facility
     * or group) simply matches nothing on that branch, which is what an unplaced device means.
     */
    @Query("SELECT ca FROM ContentAssignment ca "
           + "WHERE ca.status = 'CONFIRMED' "
           + "AND ca.startTime <= :to AND ca.endTime > :from "
           + "AND (ca.deletedAt IS NULL OR ca.deletedAt > :from) "
           + "AND ((ca.targetType = 'REGION' AND ca.targetId = :regionId) "
           + "  OR (ca.targetType = 'FACILITY' AND ca.targetId = :facilityId) "
           + "  OR (ca.targetType = 'DEVICE_GROUP' AND ca.targetId = :deviceGroupId))")
    List<ContentAssignment> findHistoricalCandidates(@Param("regionId") Long regionId,
                                                     @Param("facilityId") Long facilityId,
                                                     @Param("deviceGroupId") Long deviceGroupId,
                                                     @Param("from") Instant from,
                                                     @Param("to") Instant to);

    /**
     * Active assignments that reference a specific playlist. Drives the playlist-
     * mutation instant-push path ({@code PlaylistReorderedSyncPushListener}) — when
     * an operator edits a playlist, every device currently bound to it via an
     * active assignment should receive a SYNC notification within ~1s. Mirrors
     * {@link #findActiveAtTime} (time-window predicate) and
     * {@link #countActiveAssignmentsByPlaylistId} (playlist filter).
     */
    @Query("SELECT ca FROM ContentAssignment ca " +
           "WHERE ca.playlist.id = :playlistId " +
           "AND ca.deletedAt IS NULL " +
           "AND ca.status = 'CONFIRMED' " +
           "AND ca.startTime <= :now AND ca.endTime > :now")
    List<ContentAssignment> findActiveByPlaylistId(@Param("playlistId") Long playlistId,
                                                    @Param("now") Instant now);

    @Query("SELECT ca FROM ContentAssignment ca " +
           "WHERE ca.status = 'DRAFT' " +
           "AND ca.deletedAt IS NULL " +
           "AND ca.createdAt < :threshold")
    List<ContentAssignment> findExpiredDrafts(@Param("threshold") Instant threshold);

    /**
     * Count of <i>active</i> assignments referencing a playlist. "Active" here means
     * neither {@code DRAFT} (not yet committed) nor {@code CANCELLED} (logically gone)
     * and not soft-deleted. Drives the 409 guard from {@code DELETE /api/playlists/{id}} —
     * removing a playlist that an in-flight assignment relies on would silently break
     * scheduled playback, so the API refuses and the operator must cancel the assignments
     * first.
     */
    @Query("SELECT COUNT(ca) FROM ContentAssignment ca " +
           "WHERE ca.playlist.id = :playlistId " +
           "AND ca.deletedAt IS NULL " +
           "AND ca.status NOT IN ('DRAFT', 'CANCELLED')")
    long countActiveAssignmentsByPlaylistId(@Param("playlistId") Long playlistId);

    /**
     * Count of CONFIRMED, non-soft-deleted assignments targeting a specific device
     * group. Drives the 409 guard from {@code DELETE /api/device-groups/{id}}: deleting
     * a group still wired to a confirmed assignment would leave the assignment dangling
     * (its target id no longer resolves), so the API refuses and the operator must
     * retarget or cancel the assignment first.
     *
     * <p>{@code DRAFT} assignments don't count — they're not yet committed and the
     * operator can revise them. {@code CANCELLED} assignments don't count — they're
     * logically gone.
     */
    @Query("SELECT COUNT(ca) FROM ContentAssignment ca " +
           "WHERE ca.targetType = 'DEVICE_GROUP' " +
           "AND ca.targetId = :deviceGroupId " +
           "AND ca.status = 'CONFIRMED' " +
           "AND ca.deletedAt IS NULL")
    long countConfirmedAssignmentsForDeviceGroup(@Param("deviceGroupId") Long deviceGroupId);

    /**
     * Count of CONFIRMED, non-soft-deleted assignments targeting a specific facility.
     * Drives the 409 guard from {@code DELETE /api/facilities/{id}} — same pattern as
     * {@link #countConfirmedAssignmentsForDeviceGroup}, mutatis mutandis. {@code DRAFT}
     * (not yet committed) and {@code CANCELLED} (logically gone) assignments do not
     * count.
     */
    @Query("SELECT COUNT(ca) FROM ContentAssignment ca " +
           "WHERE ca.targetType = 'FACILITY' " +
           "AND ca.targetId = :facilityId " +
           "AND ca.status = 'CONFIRMED' " +
           "AND ca.deletedAt IS NULL")
    long countConfirmedAssignmentsForFacility(@Param("facilityId") Long facilityId);
}
