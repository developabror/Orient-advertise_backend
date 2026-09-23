package uz.orientadvertise.services.domain.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.model.ContentFile;

public interface ContentFileRepository extends JpaRepository<ContentFile, Long> {

    /**
     * Deterministic order for the content listing: newest first, {@code id} as the tiebreaker.
     *
     * <p><b>Why a tiebreaker is not optional.</b> {@link #findFiltered} and
     * {@link #findFilteredScoped} carry no {@code ORDER BY} of their own, so without a sort the
     * page membership is unspecified DB order — and it <i>shifts as the transcode pipeline UPDATEs
     * rows</i>, which is enough to move a row between pages while an operator is paging. Two rows
     * sharing a {@code createdAt} are only totally ordered once {@code id} breaks the tie, and
     * {@code id} is exactly the key the dashboard frontend structurally cannot send (it binds a
     * single {@code sort} parameter per request).
     *
     * <p>Applied by {@code ContentListService}: an explicit caller sort still wins, but the
     * {@code id} tiebreaker is always appended.
     */
    Sort DEFAULT_LISTING_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    List<ContentFile> findByProjectIdAndDeletedAtIsNull(Long projectId);

    Optional<ContentFile> findByIdAndDeletedAtIsNull(Long id);

    List<ContentFile> findByStatusAndDeletedAtIsNull(ContentFile.Status status);

    /**
     * Legacy content files needing metadata reconciliation: READY with a processed object but no
     * {@code checksum} yet (transcoded before checksums were recorded). The startup reconciler
     * backfills their {@code checksum} + true {@code sizeBytes} from the stored object. Files
     * processed by the current pipeline already carry a checksum, so they're naturally excluded.
     */
    List<ContentFile> findByStatusAndChecksumIsNullAndProcessedStorageKeyIsNotNullAndDeletedAtIsNull(
            ContentFile.Status status);

    /**
     * Filtered, paginated content listing for {@code GET /api/content}. Soft-deleted files
     * are always excluded. Each filter parameter is optional — pass {@code null} to skip
     * that predicate. {@code name} is matched case-insensitively as a substring.
     *
     * <p>The split between this method and {@link #findFilteredScoped} keeps the JPQL
     * straightforward — a single {@code IN :ids} clause that switches on/off via a
     * boolean would force an empty-list bind on the unscoped path, which Hibernate
     * rejects on some versions.
     */
    @Query("SELECT cf FROM ContentFile cf " +
           "WHERE cf.deletedAt IS NULL " +
           "AND (:projectId IS NULL OR cf.project.id = :projectId) " +
           "AND (:status IS NULL OR cf.status = :status) " +
           "AND (CAST(:name AS string) IS NULL OR LOWER(cf.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))")
    Page<ContentFile> findFiltered(@Param("projectId") Long projectId,
                                    @Param("status") ContentFile.Status status,
                                    @Param("name") String name,
                                    Pageable pageable);

    /**
     * Same filters as {@link #findFiltered} but additionally constrained to {@code ids}.
     * Used by the ADVERTISER role to limit the listing to content files the advertiser is
     * linked to via {@code advertiser_content_access}. {@code ids} must be non-empty —
     * the caller short-circuits to {@code Page.empty()} when the advertiser has no grants.
     */
    @Query("SELECT cf FROM ContentFile cf " +
           "WHERE cf.deletedAt IS NULL " +
           "AND cf.id IN :ids " +
           "AND (:projectId IS NULL OR cf.project.id = :projectId) " +
           "AND (:status IS NULL OR cf.status = :status) " +
           "AND (CAST(:name AS string) IS NULL OR LOWER(cf.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))")
    Page<ContentFile> findFilteredScoped(@Param("projectId") Long projectId,
                                          @Param("status") ContentFile.Status status,
                                          @Param("name") String name,
                                          @Param("ids") Collection<Long> ids,
                                          Pageable pageable);

    /**
     * Ids of non-deleted content uploaded by the given username — the ownership half of an
     * operator's visible content set ({@code owned ∪ granted}). Soft-deleted rows are excluded.
     */
    @Query("SELECT cf.id FROM ContentFile cf WHERE cf.uploadedBy = :username AND cf.deletedAt IS NULL")
    List<Long> findIdsByUploadedBy(@Param("username") String username);

    /**
     * The file's project id — {@code null} for orphan content, and {@code null} for an unknown id.
     * Used only to route dashboard content-status frames to the right operator sessions.
     *
     * <p>This exists because the transcode pipeline and the sweeper run with <b>no persistence
     * context</b>: reading {@code file.getProject().getId()} there initialises the LAZY association
     * and throws {@code LazyInitializationException}. Hibernate resolves {@code c.project.id}
     * against the FK column, so this is a single-row read with no join.
     */
    @Query("SELECT c.project.id FROM ContentFile c WHERE c.id = :id")
    Long findProjectIdById(@Param("id") Long id);

    // ---------------------------------------------------------------------------------------
    // Transcode lease (v1.0.132).
    //
    // Every statement below is ONE atomic UPDATE guarded by a compare-and-set on `status`, and
    // every one carries @Transactional(REQUIRES_NEW) so it commits the moment it returns.
    //
    // That is deliberate and differs from the ambient convention in this package (where @Modifying
    // methods inherit the caller's transaction). Two reasons, and the second one is not optional:
    //
    //   1. The transcode pipeline runs on a pool thread with NO surrounding transaction, precisely
    //      so a 15-minute ffmpeg run never holds a Hikari connection. Self-committing statements
    //      give the pipeline its three short transactions — claim, lease, terminal — and make the
    //      "log success only after commit" ordering a property of the code shape rather than a
    //      callback everyone must remember to register.
    //
    //   2. REQUIRES_NEW, not REQUIRED, because the claim is issued from a
    //      @TransactionalEventListener(AFTER_COMMIT). Inside that callback the completed
    //      transaction's resources are STILL BOUND to the thread, so a REQUIRED statement joins a
    //      transaction that has already committed and Hibernate throws
    //      "TransactionRequiredException: no transaction is in progress" — which the synchronization
    //      machinery then swallows, silently losing the dispatch. REQUIRES_NEW suspends the dead
    //      transaction and opens a live one. Downgrading any of these to REQUIRED reintroduces the
    //      exact class of silent loss this release exists to remove.
    //
    // flushAutomatically: a caller that already mutated the managed entity in the same transaction
    // would otherwise have its dirty state discarded by the clear (the v1.0.124 group-volume bug).
    // clearAutomatically: nobody should read a stale entity after a bulk write.
    //
    // Statuses are always BOUND parameters, never HQL enum literals: literal syntax for a NESTED
    // enum is dialect- and version-sensitive, and a bind works identically on H2 (PG mode, tests)
    // and PostgreSQL (production). `updatedAt` is set explicitly because a bulk update bypasses the
    // entity setters. The `default` wrappers below hold the constants so call sites stay readable.
    // ---------------------------------------------------------------------------------------

    /**
     * Atomically claim a row for transcoding: compare-and-set {@code status} from {@code expected}
     * to {@code TRANSCODING} and stamp {@code transcodeQueuedAt}. The lease
     * ({@code transcodeStartedAt}) stays null and no attempt is counted until the encode really
     * begins ({@link #beginTranscode}) — a job waiting in the queue is not running (LOGIC-08).
     *
     * <p><b>This is the only legitimate way to start a transcode.</b> Claiming rather than merely
     * selecting is what makes double-dispatch impossible without a distributed lock: of N callers
     * racing on the same row, exactly one sees a return value of 1, and only that one dispatches.
     *
     * @return 1 when this caller won the claim; 0 when the row was already claimed, terminal,
     *         soft-deleted or gone
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE ContentFile c
               SET c.status = :claimed,
                   c.transcodeQueuedAt = :now,
                   c.transcodeStartedAt = NULL,
                   c.updatedAt = :now
             WHERE c.id = :id
               AND c.status = :expected
               AND c.deletedAt IS NULL
            """)
    int claimForTranscode(@Param("id") Long id,
                          @Param("expected") ContentFile.Status expected,
                          @Param("claimed") ContentFile.Status claimed,
                          @Param("now") Instant now);

    /** @see #claimForTranscode(Long, ContentFile.Status, ContentFile.Status, Instant) */
    default int claimForTranscode(Long id, ContentFile.Status expected, Instant now) {
        return claimForTranscode(id, expected, ContentFile.Status.TRANSCODING, now);
    }

    /**
     * The pipeline's start guard: take the lease and count the attempt at the moment the encode
     * actually starts.
     *
     * <p>Only a claimed job that has <b>not started yet</b> ({@code transcodeStartedAt IS NULL}) gets
     * through. A return of 0 means someone else already started it — a duplicate queue entry — or
     * the row is no longer claimed at all (terminal, soft-deleted), so a duplicate dispatch
     * degrades to a harmless no-op instead of a second, concurrent encode of the same file.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE ContentFile c
               SET c.transcodeStartedAt = :now,
                   c.transcodeAttempts = c.transcodeAttempts + 1,
                   c.updatedAt = :now
             WHERE c.id = :id
               AND c.status = :transcoding
               AND c.transcodeStartedAt IS NULL
               AND c.deletedAt IS NULL
            """)
    int beginTranscode(@Param("id") Long id,
                       @Param("transcoding") ContentFile.Status transcoding,
                       @Param("now") Instant now);

    /** @see #beginTranscode(Long, ContentFile.Status, Instant) */
    default int beginTranscode(Long id, Instant now) {
        return beginTranscode(id, ContentFile.Status.TRANSCODING, now);
    }

    /**
     * Put a stalled {@code TRANSCODING} row back in the queue: an encode whose lease expired
     * ({@code transcodeStartedAt < leaseCutoff}), or a queued job that never started and was queued
     * before {@code queueCutoff} — both as seen by
     * {@link #findStalledTranscodeCandidates}. The predicate is re-checked inside the UPDATE, so a
     * task that began between the sweeper's read and this write makes it return 0 rather than
     * resetting a live encode. No attempt is counted here; {@link #beginTranscode} counts it.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE ContentFile c
               SET c.transcodeQueuedAt = :now,
                   c.transcodeStartedAt = NULL,
                   c.updatedAt = :now
             WHERE c.id = :id
               AND c.status = :transcoding
               AND c.deletedAt IS NULL
               AND ((c.transcodeStartedAt IS NOT NULL AND c.transcodeStartedAt < :leaseCutoff)
                 OR (c.transcodeStartedAt IS NULL
                     AND (c.transcodeQueuedAt IS NULL OR c.transcodeQueuedAt < :queueCutoff)))
            """)
    int requeueStalledTranscode(@Param("id") Long id,
                                @Param("transcoding") ContentFile.Status transcoding,
                                @Param("leaseCutoff") Instant leaseCutoff,
                                @Param("queueCutoff") Instant queueCutoff,
                                @Param("now") Instant now);

    /** @see #requeueStalledTranscode(Long, ContentFile.Status, Instant, Instant, Instant) */
    default int requeueStalledTranscode(Long id, Instant leaseCutoff, Instant queueCutoff, Instant now) {
        return requeueStalledTranscode(id, ContentFile.Status.TRANSCODING, leaseCutoff, queueCutoff, now);
    }

    /**
     * Terminal success. {@code sizeBytes} is the PROCESSED object's size — the bytes a device
     * actually downloads — and {@code checksum} is that object's SHA-256; a device rejects a file
     * whose downloaded size does not match. A null {@code thumbnailKey} clears any previous poster
     * on purpose: the processed object was replaced, so an older thumbnail is stale.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE ContentFile c
               SET c.status = :ready,
                   c.processedStorageKey = :processedKey,
                   c.sizeBytes = :sizeBytes,
                   c.checksum = :checksum,
                   c.thumbnailStorageKey = :thumbnailKey,
                   c.durationSeconds = :durationSeconds,
                   c.invalidReason = NULL,
                   c.transcodeLastError = NULL,
                   c.updatedAt = :now
             WHERE c.id = :id
               AND c.status = :transcoding
            """)
    int markTranscodeReady(@Param("id") Long id,
                           @Param("processedKey") String processedKey,
                           @Param("sizeBytes") long sizeBytes,
                           @Param("checksum") String checksum,
                           @Param("thumbnailKey") String thumbnailKey,
                           @Param("durationSeconds") Integer durationSeconds,
                           @Param("ready") ContentFile.Status ready,
                           @Param("transcoding") ContentFile.Status transcoding,
                           @Param("now") Instant now);

    /**
     * Soft-deleted files that still own bytes in object storage, oldest deletion first (VG-08).
     * Feeds the sweeper that reclaims them; a row is picked up only after the grace period, and
     * only while it still has a key to clear, so a swept file is never revisited.
     */
    @Query("SELECT c FROM ContentFile c "
           + "WHERE c.deletedAt IS NOT NULL AND c.deletedAt < :cutoff "
           + "AND (c.processedStorageKey IS NOT NULL OR c.thumbnailStorageKey IS NOT NULL) "
           + "ORDER BY c.deletedAt ASC")
    List<ContentFile> findDeletedWithStorageObjects(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Record that a deleted file's bytes are gone. Clearing the keys is what makes the sweep
     * idempotent — and it is done ONLY after the objects are actually removed, so a storage outage
     * leaves the row for the next run rather than losing track of the bytes forever.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE ContentFile c SET c.processedStorageKey = NULL, c.thumbnailStorageKey = NULL, "
           + "c.updatedAt = :now WHERE c.id = :id AND c.deletedAt IS NOT NULL")
    int clearStorageKeysOfDeleted(@Param("id") Long id, @Param("now") Instant now);

    /** @see #markTranscodeReady */
    default int markTranscodeReady(Long id, String processedKey, long sizeBytes, String checksum,
                                    String thumbnailKey, Integer durationSeconds, Instant now) {
        return markTranscodeReady(id, processedKey, sizeBytes, checksum, thumbnailKey, durationSeconds,
                ContentFile.Status.READY, ContentFile.Status.TRANSCODING, now);
    }

    /** Terminal failure of the transcode itself (ffmpeg error / timeout / storage error). Retryable. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE ContentFile c
               SET c.status = :failed,
                   c.transcodeLastError = :error,
                   c.updatedAt = :now
             WHERE c.id = :id
               AND c.status = :transcoding
            """)
    int markTranscodeFailed(@Param("id") Long id,
                            @Param("error") String error,
                            @Param("failed") ContentFile.Status failed,
                            @Param("transcoding") ContentFile.Status transcoding,
                            @Param("now") Instant now);

    /** @see #markTranscodeFailed */
    default int markTranscodeFailed(Long id, String error, Instant now) {
        return markTranscodeFailed(id, error, ContentFile.Status.FAILED,
                ContentFile.Status.TRANSCODING, now);
    }

    /**
     * Terminal rejection of the CONTENT (unreadable container, zero duration, ffprobe refusal).
     * Distinct from FAILED: retrying cannot help, so the sweeper never re-drives an INVALID row.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE ContentFile c
               SET c.status = :invalid,
                   c.invalidReason = :reason,
                   c.transcodeLastError = NULL,
                   c.updatedAt = :now
             WHERE c.id = :id
               AND c.status = :transcoding
            """)
    int markTranscodeInvalid(@Param("id") Long id,
                             @Param("reason") String reason,
                             @Param("invalid") ContentFile.Status invalid,
                             @Param("transcoding") ContentFile.Status transcoding,
                             @Param("now") Instant now);

    /** @see #markTranscodeInvalid */
    default int markTranscodeInvalid(Long id, String reason, Instant now) {
        return markTranscodeInvalid(id, reason, ContentFile.Status.INVALID,
                ContentFile.Status.TRANSCODING, now);
    }

    /**
     * Give up on a row that has burned its attempt budget: park it in FAILED with the reason, so an
     * operator can see why nothing retries it any more. Unlike the terminal writers above, this one
     * is guarded on the caller-supplied {@code expected} status — the sweeper calls it for exhausted
     * UPLOADED rows as well as TRANSCODING ones.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("""
            UPDATE ContentFile c
               SET c.status = :failed,
                   c.transcodeLastError = :error,
                   c.updatedAt = :now
             WHERE c.id = :id
               AND c.status = :expected
               AND c.deletedAt IS NULL
            """)
    int abandonTranscode(@Param("id") Long id,
                         @Param("expected") ContentFile.Status expected,
                         @Param("error") String error,
                         @Param("failed") ContentFile.Status failed,
                         @Param("now") Instant now);

    /** @see #abandonTranscode */
    default int abandonTranscode(Long id, ContentFile.Status expected, String error, Instant now) {
        return abandonTranscode(id, expected, error, ContentFile.Status.FAILED, now);
    }

    /**
     * Clear the attempt budget. Used only by the operator-triggered retranscode: a human asking
     * again is not the same as an automatic retry and must not be capped by earlier failures.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE ContentFile c SET c.transcodeAttempts = 0, c.updatedAt = :now WHERE c.id = :id")
    int resetTranscodeAttempts(@Param("id") Long id, @Param("now") Instant now);

    /**
     * Case 1 — <b>lost dispatch</b>. Rows that have sat in UPLOADED past the grace period: either
     * the after-commit dispatch never reached the executor (rejection, or a crash between commit
     * and dispatch), or this is legacy damage from the pre-v1.0.132 pre-commit dispatch race.
     *
     * <p>{@code createdAt} is the right clock here because the row has never been claimed.
     */
    @Query("""
            SELECT c FROM ContentFile c
             WHERE c.status = :uploaded
               AND c.deletedAt IS NULL
               AND c.createdAt < :cutoff
             ORDER BY c.id
            """)
    List<ContentFile> findLostDispatchCandidates(@Param("uploaded") ContentFile.Status uploaded,
                                                  @Param("cutoff") Instant cutoff,
                                                  Pageable pageable);

    /** @see #findLostDispatchCandidates */
    default List<ContentFile> findLostDispatchCandidates(Instant cutoff, Pageable pageable) {
        return findLostDispatchCandidates(ContentFile.Status.UPLOADED, cutoff, pageable);
    }

    /**
     * Case 2 — <b>stalled transcode</b>. Rows in {@code TRANSCODING} that may have lost their
     * worker:
     * <ul>
     *   <li>an encode that <b>started</b> and whose lease expired — the process was killed
     *       mid-ffmpeg. Keyed on the lease, never on {@code updatedAt}, which does not advance
     *       during an encode, so an updatedAt-keyed sweeper would re-dispatch a slow transcode;</li>
     *   <li>a job that was claimed but <b>never started</b>, queued before {@code queueCutoff} — its
     *       queue entry may be gone (rejected, process restarted). The lease never applies to it
     *       (LOGIC-08).</li>
     * </ul>
     * Rows in {@code heldIds} — the files this process still has queued or running — are excluded:
     * they are waiting or working, not lost, and leaving them in would let a long queue fill the
     * page and keep genuinely lost rows from being seen.
     *
     * @param heldIds never empty: pass a sentinel such as {@code -1L} when nothing is held, since an
     *                empty {@code NOT IN} list is not portable SQL
     */
    @Query("""
            SELECT c FROM ContentFile c
             WHERE c.status = :transcoding
               AND c.deletedAt IS NULL
               AND c.id NOT IN :heldIds
               AND ((c.transcodeStartedAt IS NOT NULL AND c.transcodeStartedAt < :leaseCutoff)
                 OR (c.transcodeStartedAt IS NULL
                     AND (c.transcodeQueuedAt IS NULL OR c.transcodeQueuedAt < :queueCutoff)))
             ORDER BY c.id
            """)
    List<ContentFile> findStalledTranscodeCandidates(@Param("transcoding") ContentFile.Status transcoding,
                                                      @Param("leaseCutoff") Instant leaseCutoff,
                                                      @Param("queueCutoff") Instant queueCutoff,
                                                      @Param("heldIds") java.util.Collection<Long> heldIds,
                                                      Pageable pageable);

    /** @see #findStalledTranscodeCandidates */
    default List<ContentFile> findStalledTranscodeCandidates(Instant leaseCutoff, Instant queueCutoff,
                                                              java.util.Collection<Long> heldIds,
                                                              Pageable pageable) {
        var excluded = heldIds.isEmpty() ? List.of(-1L) : heldIds;
        return findStalledTranscodeCandidates(ContentFile.Status.TRANSCODING, leaseCutoff, queueCutoff,
                excluded, pageable);
    }

    /**
     * Encodes that started before {@code leaseCutoff} and are still {@code TRANSCODING}. After a
     * sweep has re-queued the crashed ones, what is left is running in this process past its lease —
     * a hung ffmpeg or ffprobe holding a pool slot. The health check and the sweeper's alarm read it.
     */
    @Query("""
            SELECT COUNT(c) FROM ContentFile c
             WHERE c.status = :transcoding
               AND c.deletedAt IS NULL
               AND c.transcodeStartedAt < :leaseCutoff
            """)
    long countEncodesPastLease(@Param("transcoding") ContentFile.Status transcoding,
                               @Param("leaseCutoff") Instant leaseCutoff);

    /** @see #countEncodesPastLease */
    default long countEncodesPastLease(Instant leaseCutoff) {
        return countEncodesPastLease(ContentFile.Status.TRANSCODING, leaseCutoff);
    }

    /**
     * Case 3 — <b>retryable failure</b>. A FAILED row is a transcode error, not a content rejection
     * (that is INVALID), so it is worth retrying up to the attempt cap. The lease-age predicate
     * spaces retries out instead of re-running the same doomed encode on every sweep.
     */
    @Query("""
            SELECT c FROM ContentFile c
             WHERE c.status = :failed
               AND c.deletedAt IS NULL
               AND c.transcodeAttempts < :maxAttempts
               AND (c.transcodeStartedAt IS NULL OR c.transcodeStartedAt < :retryCutoff)
             ORDER BY c.id
            """)
    List<ContentFile> findRetryableFailedCandidates(@Param("failed") ContentFile.Status failed,
                                                     @Param("maxAttempts") int maxAttempts,
                                                     @Param("retryCutoff") Instant retryCutoff,
                                                     Pageable pageable);

    /** @see #findRetryableFailedCandidates */
    default List<ContentFile> findRetryableFailedCandidates(int maxAttempts, Instant retryCutoff,
                                                             Pageable pageable) {
        return findRetryableFailedCandidates(ContentFile.Status.FAILED, maxAttempts, retryCutoff, pageable);
    }

    /**
     * Backlog gauge for the health indicator and the sweeper's alert log: files still sitting in
     * UPLOADED past {@code cutoff}. A non-zero value means uploads are not being transcoded — the
     * single metric that would have surfaced the v1.0.132 incident in minutes rather than waiting
     * for a user complaint.
     */
    @Query("""
            SELECT COUNT(c) FROM ContentFile c
             WHERE c.status = :uploaded
               AND c.deletedAt IS NULL
               AND c.createdAt < :cutoff
            """)
    long countStaleUploaded(@Param("uploaded") ContentFile.Status uploaded, @Param("cutoff") Instant cutoff);

    /** @see #countStaleUploaded */
    default long countStaleUploaded(Instant cutoff) {
        return countStaleUploaded(ContentFile.Status.UPLOADED, cutoff);
    }
}
