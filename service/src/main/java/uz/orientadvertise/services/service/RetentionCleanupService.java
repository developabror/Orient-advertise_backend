package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;

/**
 * Nightly retention cleanup. Deletes {@code event} and {@code playback_log} rows older
 * than 90 days in 1000-row batches, each batch in its own transaction so a partial run
 * still commits progress.
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
 *   <li><b>Bounded per-run work.</b> {@value #MAX_BATCHES_PER_RUN} batches × 1000 rows
 *       per table caps a single run at ~100k deletes per table so a backlog after a long
 *       outage doesn't run for hours.</li>
 * </ul>
 */
@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    public static final Duration RETENTION = Duration.ofDays(90);
    public static final int BATCH_SIZE = 1000;
    public static final int MAX_BATCHES_PER_RUN = 100;
    public static final String SCHEDULE_ZONE = "Asia/Karachi";
    private static final ZoneId WINDOW_ZONE = ZoneId.of(SCHEDULE_ZONE);
    private static final int WINDOW_START_HOUR = 1;
    private static final int WINDOW_END_HOUR = 4;

    private final EventRepository eventRepository;
    private final PlaybackLogRepository playbackLogRepository;
    private final RetentionCleanupService self;

    @Value("${app.retention.guard-window:true}")
    private boolean guardWindowEnabled;

    public RetentionCleanupService(EventRepository eventRepository,
                                    PlaybackLogRepository playbackLogRepository,
                                    @Lazy RetentionCleanupService self) {
        this.eventRepository = eventRepository;
        this.playbackLogRepository = playbackLogRepository;
        this.self = self;
    }

    @Scheduled(cron = "0 0 2 * * ?", zone = SCHEDULE_ZONE)
    public void scheduledRun() {
        runCleanup();
    }

    public CleanupResult runCleanup() {
        Instant now = Instant.now();
        if (guardWindowEnabled && !isInMaintenanceWindow(now)) {
            log.info("Retention cleanup skipped — outside maintenance window ({}-{}h {})",
                    WINDOW_START_HOUR, WINDOW_END_HOUR, WINDOW_ZONE);
            return new CleanupResult(0, 0, true);
        }

        Instant threshold = now.minus(RETENTION);
        log.info("Retention cleanup starting [threshold={}]", threshold);

        int eventsDeleted = drainEvents(threshold);
        int playbacksDeleted = drainPlaybackLogs(threshold);

        log.info("Retention cleanup done: deleted events={}, playback_logs={}, threshold={}",
                eventsDeleted, playbacksDeleted, threshold);
        return new CleanupResult(eventsDeleted, playbacksDeleted, false);
    }

    private int drainEvents(Instant threshold) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int rows;
            try {
                rows = self.deleteExpiredEventBatch(threshold);
            } catch (Exception e) {
                log.warn("Event batch {} failed (stopping): {}", i, e.getMessage());
                break;
            }
            total += rows;
            if (rows < BATCH_SIZE) break;
        }
        return total;
    }

    private int drainPlaybackLogs(Instant threshold) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int rows;
            try {
                rows = self.deleteExpiredPlaybackBatch(threshold);
            } catch (Exception e) {
                log.warn("Playback batch {} failed (stopping): {}", i, e.getMessage());
                break;
            }
            total += rows;
            if (rows < BATCH_SIZE) break;
        }
        return total;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredEventBatch(Instant threshold) {
        List<Long> ids = eventRepository.findExpiredIdsSkippingOpenIncidents(
                threshold, PageRequest.of(0, BATCH_SIZE));
        if (ids.isEmpty()) return 0;
        eventRepository.deleteAllByIdInBatch(ids);
        return ids.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredPlaybackBatch(Instant threshold) {
        List<Long> ids = playbackLogRepository.findIdsOlderThan(
                threshold, PageRequest.of(0, BATCH_SIZE));
        if (ids.isEmpty()) return 0;
        playbackLogRepository.deleteAllByIdInBatch(ids);
        return ids.size();
    }

    static boolean isInMaintenanceWindow(Instant now) {
        int hour = ZonedDateTime.ofInstant(now, WINDOW_ZONE).getHour();
        return hour >= WINDOW_START_HOUR && hour < WINDOW_END_HOUR;
    }

    public record CleanupResult(int eventsDeleted, int playbackLogsDeleted, boolean skipped) {}
}
