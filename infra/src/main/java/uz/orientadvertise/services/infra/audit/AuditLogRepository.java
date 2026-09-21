package uz.orientadvertise.services.infra.audit;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Oldest-first ids of expired audit rows, for {@code RetentionCleanupService} to delete in
     * bounded batches.
     *
     * <p>This table is the fattest one on the box: unlike {@code event} or {@code playback_log},
     * every row carries the full HTTP request <b>and</b> response body (masked by
     * {@code SensitiveFieldMasker}). Nothing pruned it before v1.0.133, on a volume that was 96%
     * full.
     *
     * <p>Ordered by id, which is monotonic with {@code timestamp} here (rows are only ever appended
     * by {@code AsyncAuditWriter}), so batches walk the backlog from the oldest end and the
     * {@code idx_audit_log_timestamp} index serves the predicate.
     */
    @Query("SELECT a.id FROM AuditLog a WHERE a.timestamp < :threshold ORDER BY a.id ASC")
    List<Long> findIdsOlderThan(@Param("threshold") Instant threshold, Pageable pageable);
}
