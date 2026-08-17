package uz.orientadvertise.services.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.orientadvertise.services.domain.model.PlaybackLog;

public interface PlaybackLogRepository extends JpaRepository<PlaybackLog, Long> {

    List<PlaybackLog> findByDeviceIdAndPlayedAtBetweenOrderByPlayedAtDesc(
            Long deviceId, Instant from, Instant to);

    List<PlaybackLog> findByContentFileIdOrderByPlayedAtDesc(Long contentFileId);

    boolean existsByDeviceIdAndContentFileIdAndPlayedAt(Long deviceId, Long contentFileId, Instant playedAt);

    /**
     * Idempotent single-row insert. Returns 1 when the row was written, 0 when
     * {@code (device_id, content_file_id, played_at)} already exists — the database skips the
     * duplicate instead of raising SQLSTATE 23505, so the caller's transaction stays usable for
     * the rest of the batch. Catching the violation after the fact cannot achieve this: by the
     * time a DataIntegrityViolationException is visible, PostgreSQL has aborted the transaction
     * (25P02 on every later statement) and Hibernate has already called markForRollbackOnly()
     * on the session. Neither is undoable from a catch block.
     *
     * <p>The conflict target is deliberately OMITTED. H2 2.3.232 (MODE=PostgreSQL — the test
     * profile) accepts ONLY the bare {@code ON CONFLICT DO NOTHING}; adding an explicit target
     * is a JdbcSQLSyntaxErrorException there. Targetless is exact for this table because
     * playback_log has exactly two unique constraints: the identity PK (a generated value can
     * never collide) and uq_playback_dedup. If a third unique constraint is ever added to
     * playback_log, this statement would silently swallow that conflict too — add an explicit
     * target then, and drop H2 for that test.
     *
     * <p>The CASTs pin the types of the two nullable parameters; without them a null bind
     * reaches PostgreSQL as an untyped NULL that must be inferred from context.
     */
    @Modifying
    @Query(value = """
            INSERT INTO playback_log
                (device_id, content_file_id, assignment_id, played_at, duration_seconds, reported_at)
            VALUES (:deviceId, :contentFileId, CAST(:assignmentId AS BIGINT), :playedAt,
                    CAST(:durationSeconds AS INTEGER), :reportedAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringDuplicate(@Param("deviceId") Long deviceId,
                                @Param("contentFileId") Long contentFileId,
                                @Param("assignmentId") Long assignmentId,
                                @Param("playedAt") Instant playedAt,
                                @Param("durationSeconds") Integer durationSeconds,
                                @Param("reportedAt") Instant reportedAt);

    @Query("SELECT pl.id FROM PlaybackLog pl WHERE pl.playedAt < :threshold ORDER BY pl.id ASC")
    List<Long> findIdsOlderThan(@Param("threshold") Instant threshold, Pageable pageable);

    @Query("SELECT COUNT(pl) FROM PlaybackLog pl " +
           "WHERE pl.contentFile.id = :contentFileId " +
           "AND pl.playedAt >= :from AND pl.playedAt <= :to " +
           "AND (:deviceId IS NULL OR pl.device.id = :deviceId)")
    long countByContentInRange(@Param("contentFileId") Long contentFileId,
                                @Param("deviceId") Long deviceId,
                                @Param("from") Instant from,
                                @Param("to") Instant to);

    @Query("SELECT pl.device.id, pl.device.name, COUNT(pl) FROM PlaybackLog pl " +
           "WHERE pl.contentFile.id = :contentFileId " +
           "AND pl.playedAt >= :from AND pl.playedAt <= :to " +
           "AND (:deviceId IS NULL OR pl.device.id = :deviceId) " +
           "GROUP BY pl.device.id, pl.device.name " +
           "ORDER BY COUNT(pl) DESC")
    List<Object[]> countPerDeviceForContent(@Param("contentFileId") Long contentFileId,
                                              @Param("deviceId") Long deviceId,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to);

    @Query("SELECT pl.playedAt FROM PlaybackLog pl " +
           "WHERE pl.contentFile.id = :contentFileId " +
           "AND pl.playedAt >= :from AND pl.playedAt <= :to " +
           "AND (:deviceId IS NULL OR pl.device.id = :deviceId) " +
           "ORDER BY pl.playedAt DESC")
    org.springframework.data.domain.Page<Instant> findTimestampsForContent(
            @Param("contentFileId") Long contentFileId,
            @Param("deviceId") Long deviceId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /**
     * Operator-scoped per-device counts: same as {@link #countPerDeviceForContent} but the
     * device dimension is intersected with the operator's projects via
     * {@code device.region.project.id}. Only called with a non-empty {@code projectIds} (the
     * service short-circuits the empty operator scope). The operator's {@code totalPlayCount}
     * is summed from these visible rows — never re-queried from the unscoped scalar — so a
     * foreign-project play can leak through neither the rows nor the total.
     */
    @Query("SELECT pl.device.id, pl.device.name, COUNT(pl) FROM PlaybackLog pl " +
           "WHERE pl.contentFile.id = :contentFileId " +
           "AND pl.playedAt >= :from AND pl.playedAt <= :to " +
           "AND (:deviceId IS NULL OR pl.device.id = :deviceId) " +
           "AND pl.device.region.project.id IN :projectIds " +
           "GROUP BY pl.device.id, pl.device.name " +
           "ORDER BY COUNT(pl) DESC")
    List<Object[]> countPerDeviceForContentScoped(@Param("contentFileId") Long contentFileId,
                                                   @Param("deviceId") Long deviceId,
                                                   @Param("from") Instant from,
                                                   @Param("to") Instant to,
                                                   @Param("projectIds") Collection<Long> projectIds);

    /** Operator-scoped timestamps: {@link #findTimestampsForContent} narrowed to the operator's projects. */
    @Query("SELECT pl.playedAt FROM PlaybackLog pl " +
           "WHERE pl.contentFile.id = :contentFileId " +
           "AND pl.playedAt >= :from AND pl.playedAt <= :to " +
           "AND (:deviceId IS NULL OR pl.device.id = :deviceId) " +
           "AND pl.device.region.project.id IN :projectIds " +
           "ORDER BY pl.playedAt DESC")
    org.springframework.data.domain.Page<Instant> findTimestampsForContentScoped(
            @Param("contentFileId") Long contentFileId,
            @Param("deviceId") Long deviceId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("projectIds") Collection<Long> projectIds,
            Pageable pageable);

    /**
     * Per-content aggregation across all content played in the range, paginated. Used by
     * the export pipeline so the caller can stream rows page-by-page rather than loading
     * the full result set. Each row: {@code [contentFileId, contentName, totalPlayCount,
     * distinctDeviceCount]}.
     */
    @Query("SELECT pl.contentFile.id, pl.contentFile.name, " +
           "COUNT(pl), COUNT(DISTINCT pl.device.id) " +
           "FROM PlaybackLog pl " +
           "WHERE pl.playedAt >= :from AND pl.playedAt <= :to " +
           "GROUP BY pl.contentFile.id, pl.contentFile.name " +
           "ORDER BY COUNT(pl) DESC")
    org.springframework.data.domain.Page<Object[]> aggregatePerContentInRange(
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    /**
     * Per-content playback aggregation for a single device over a window. Device-keyed mirror
     * of {@link #aggregatePerContentInRange} (which is content-keyed and does NOT sum duration).
     * One row per distinct content file the device played. Row shape:
     * {@code [contentFileId, contentFileName, playCount, totalDurationSeconds, missingDurationCount]}.
     *
     * <p>Duration follows the single-source-of-truth rule: prefer the play's reported
     * {@code durationSeconds}, else the content's catalog {@code contentFile.durationSeconds}
     * (V20), else 0. {@code missingDurationCount} counts plays where BOTH sources are null —
     * the service turns it into {@code durationComplete}.
     *
     * <p>Plays of since-soft-deleted content still appear (the FK row is never hard-deleted and
     * {@code contentFile.name} resolves); do NOT filter {@code deletedAt} out of this query.
     *
     * <p>Ordered {@code playCount} DESC, then {@code contentFileName} ASC, per the wire contract.
     */
    @Query("SELECT pl.contentFile.id, pl.contentFile.name, COUNT(pl), " +
           "SUM(COALESCE(pl.durationSeconds, pl.contentFile.durationSeconds, 0)), " +
           "SUM(CASE WHEN pl.durationSeconds IS NULL AND pl.contentFile.durationSeconds IS NULL THEN 1 ELSE 0 END) " +
           "FROM PlaybackLog pl " +
           "WHERE pl.device.id = :deviceId AND pl.playedAt >= :from AND pl.playedAt <= :to " +
           "GROUP BY pl.contentFile.id, pl.contentFile.name " +
           "ORDER BY COUNT(pl) DESC, pl.contentFile.name ASC")
    List<Object[]> aggregatePerContentForDevice(@Param("deviceId") Long deviceId,
                                                @Param("from") Instant from,
                                                @Param("to") Instant to);

    /** Operator-scoped {@link #aggregatePerContentInRange}; only plays on devices in {@code projectIds}. Non-empty only. */
    @Query("SELECT pl.contentFile.id, pl.contentFile.name, " +
           "COUNT(pl), COUNT(DISTINCT pl.device.id) " +
           "FROM PlaybackLog pl " +
           "WHERE pl.playedAt >= :from AND pl.playedAt <= :to " +
           "AND pl.device.region.project.id IN :projectIds " +
           "GROUP BY pl.contentFile.id, pl.contentFile.name " +
           "ORDER BY COUNT(pl) DESC")
    org.springframework.data.domain.Page<Object[]> aggregatePerContentInRangeScoped(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("projectIds") Collection<Long> projectIds,
            Pageable pageable);
}
