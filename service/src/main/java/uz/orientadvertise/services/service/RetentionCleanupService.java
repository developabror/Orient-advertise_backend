package uz.orientadvertise.services.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.ToIntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;
import uz.orientadvertise.services.infra.audit.AuditLogRepository;

/**
 * Nightly retention cleanup. Deletes {@code event}, {@code playback_log} and {@code audit_log} rows
 * older than <b>each table's own</b> window ({@link RetentionProperties}), in batches of
 * {@code app.retention.batch-size}, each batch in its own transaction so a partial run still
 * commits progress.
 *
 * <p>Scheduled at 02:00 in {@value #SCHEDULE_ZONE} (UTC+5). The cleanup also runs an
 * explicit time-of-day guard inside the run method so manual invocations (and any drift
 * from container clock skew) still respect the 01:00–04:00 maintenance window.
 *
 * <p>Edge cases:
 * <ul>
 *   <li><b>Events linked to open incidents are kept.</b> An event referenced as a
 *       non-resolved incident's {@code firstEvent} or {@code lastEvent} is excluded from
 *       the candidate id list — losing it would orphan the incident's audit trail.
 *       Once the incident is resolved, the event becomes deletable on the next run.</li>
 *   <li><b>Time-of-day guard.</b> Outside the maintenance window the call is logged and
 *       returns 0 — the scheduler firing late or a manual trigger from operations console
 *       can't accidentally hammer prod during peak hours.</li>
 *   <li><b>Bounded per-run work (DATA-01).</b> A run stops when its wall-clock budget
 *       ({@code app.retention.max-run-duration}, shared by all three tables) is spent, and each
 *       table also has a high safety stop at {@code app.retention.max-batches-per-run}. Either
 *       stop logs at INFO naming the table and the rows deleted, so a backlog is visible rather
 *       than silent. The previous fixed cap of 100 batches × 1000 rows was a *hidden* ceiling:
 *       past roughly 26 always-on devices {@code playback_log} produced more rows per day than a
 *       night could delete, so it could never shrink again.</li>
 *   <li><b>{@code audit_log} keeps a much shorter window.</b> Nothing reads it, and since
 *       v1.0.143 {@code AuditFilter} does not write device-agent traffic into it at all.</li>
 *   <li><b>{@code entity_audit_log} is deliberately NOT swept.</b> It is the business provenance
 *       trail — who changed which entity, in small rows — not HTTP traffic. Deleting it loses audit
 *       history for a negligible amount of disk. If it ever needs a retention window, give it its
 *       own, longer one rather than folding it in here.</li>
 * </ul>
 */
@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    public static final String SCHEDULE_ZONE = "Asia/Karachi";
    private static final ZoneId WINDOW_ZONE = ZoneId.of(SCHEDULE_ZONE);
    private static final int WINDOW_START_HOUR = 1;
    private static final int WINDOW_END_HOUR = 4;

    private final EventRepository eventRepository;
    private final PlaybackLogRepository playbackLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final RetentionProperties properties;
    private final RetentionCleanupService self;

    public RetentionCleanupService(EventRepository eventRepository,
                                    PlaybackLogRepository playbackLogRepository,
                                    AuditLogRepository auditLogRepository,
                                    RetentionProperties properties,
                                    @Lazy RetentionCleanupService self) {
        this.eventRepository = eventRepository;
        this.playbackLogRepository = playbackLogRepository;
        this.auditLogRepository = auditLogRepository;
        this.properties = properties;
        this.self = self;
    }

    @Scheduled(cron = "0 0 2 * * ?", zone = SCHEDULE_ZONE)
    public void scheduledRun() {
        runCleanup();
    }

    public CleanupResult runCleanup() {
        return runCleanup(Instant.now());
    }

    /**
     * The run's single time source, so the window guard and all three thresholds are computed from
     * one instant. Package-private overload: a test drives the guard from a fixed instant instead
     * of depending on what time the suite happens to run at.
     */
    CleanupResult runCleanup(Instant now) {
        if (properties.isGuardWindow() && !isInMaintenanceWindow(now)) {
            log.info("Retention cleanup skipped — outside maintenance window ({}-{}h {})",
                    WINDOW_START_HOUR, WINDOW_END_HOUR, WINDOW_ZONE);
            return new CleanupResult(0, 0, 0, true);
        }

        // One deadline for the whole run: three tables sharing a budget can't add up to an
        // eight-hour job, and whatever is left over is picked up by the next night's run.
        Instant deadline = now.plus(properties.getMaxRunDuration());
        Instant eventThreshold = now.minus(properties.getEvent());
        Instant playbackThreshold = now.minus(properties.getPlayback());
        Instant auditThreshold = now.minus(properties.getAudit());
        log.info("Retention cleanup starting [events<{}, playback_logs<{}, audit_logs<{}]",
                eventThreshold, playbackThreshold, auditThreshold);

        int eventsDeleted = drain("events", eventThreshold, deadline, self::deleteExpiredEventBatch);
        int playbacksDeleted = drain("playback_logs", playbackThreshold, deadline, self::deleteExpiredPlaybackBatch);
        int auditLogsDeleted = drain("audit_logs", auditThreshold, deadline, self::deleteExpiredAuditBatch);

        log.info("Retention cleanup done: deleted events={}, playback_logs={}, audit_logs={}",
                eventsDeleted, playbacksDeleted, auditLogsDeleted);
        return new CleanupResult(eventsDeleted, playbacksDeleted, auditLogsDeleted, false);
    }

    /**
     * Delete from one table until it is drained, or until a stop fires. Stops on: a short batch
     * (nothing left), an exception (logged once, the run moves to the next table — the batches
     * already committed stand), the run's wall-clock budget, or the per-table batch cap. The last
     * two log at INFO with the rows deleted so an unfinished table is visible in the log without
     * being an alert — the next run continues where this one stopped.
     *
     * @param batch a REQUIRES_NEW batch delete, called through {@code self} so the proxy applies
     */
    private int drain(String table, Instant threshold, Instant deadline, ToIntFunction<Instant> batch) {
        int batchSize = properties.getBatchSize();
        int maxBatches = properties.getMaxBatchesPerRun();
        int total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int rows;
            try {
                rows = batch.applyAsInt(threshold);
            } catch (Exception e) {
                log.warn("{} batch {} failed (stopping): {}", table, i, e.getMessage());
                return total;
            }
            total += rows;
            if (rows < batchSize) {
                return total;                       // drained
            }
            // Checked AFTER a batch so every table makes progress even under a tiny budget.
            if (!Instant.now().isBefore(deadline)) {
                log.info("Retention cleanup ran out of time on {} after {} rows — backlog remains, "
                        + "the next run continues from here", table, total);
                return total;
            }
        }
        log.info("Retention cleanup hit the {}-batch safety stop on {} after {} rows — backlog "
                + "remains, the next run continues from here", maxBatches, table, total);
        return total;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredEventBatch(Instant threshold) {
        List<Long> ids = eventRepository.findExpiredIdsSkippingOpenIncidents(
                threshold, PageRequest.of(0, properties.getBatchSize()));
        if (ids.isEmpty()) return 0;
        eventRepository.deleteAllByIdInBatch(ids);
        return ids.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredPlaybackBatch(Instant threshold) {
        List<Long> ids = playbackLogRepository.findIdsOlderThan(
                threshold, PageRequest.of(0, properties.getBatchSize()));
        if (ids.isEmpty()) return 0;
        playbackLogRepository.deleteAllByIdInBatch(ids);
        return ids.size();
    }

    /**
     * Same batched, own-transaction shape as the other two tables. No "skip rows still referenced"
     * exception applies here: an audit row is a standalone record of one HTTP call and nothing
     * links to it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredAuditBatch(Instant threshold) {
        List<Long> ids = auditLogRepository.findIdsOlderThan(
                threshold, PageRequest.of(0, properties.getBatchSize()));
        if (ids.isEmpty()) return 0;
        auditLogRepository.deleteAllByIdInBatch(ids);
        return ids.size();
    }

    static boolean isInMaintenanceWindow(Instant now) {
        int hour = ZonedDateTime.ofInstant(now, WINDOW_ZONE).getHour();
        return hour >= WINDOW_START_HOUR && hour < WINDOW_END_HOUR;
    }

    public record CleanupResult(int eventsDeleted, int playbackLogsDeleted, int auditLogsDeleted,
                                 boolean skipped) {}

    @Configuration
    @EnableConfigurationProperties(RetentionProperties.class)
    static class RetentionConfig {
    }
}
