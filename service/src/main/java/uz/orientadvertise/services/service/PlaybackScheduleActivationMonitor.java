package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.PlaybackSyncSchedule;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaybackSyncScheduleRepository;

/**
 * Periodic readiness poll for coordinated cut-overs (§1.4). The anchor itself is created lazily and is
 * immutable (see {@link PlaybackScheduleService}), so this monitor does not move or finalize anything —
 * it reports, for each recent/upcoming cut-over, how many of the group's devices have already downloaded
 * and confirmed the target version (the readiness rollup), and flags a live cut-over that still has
 * stragglers (they join at the live position, never at item 0). This gives ops the "wait-for-all-ready"
 * visibility the design calls for, on the same {@code @Scheduled} shape as {@link SyncTimeoutMonitor}.
 *
 * <p>Pure read orchestration — every collaborator call self-manages its own transaction, so there is no
 * per-item {@code REQUIRES_NEW} boundary to get wrong. The scan is bounded to the last {@code maxLeadCap}
 * window so it never rescans the full history of anchors.
 */
@Component
public class PlaybackScheduleActivationMonitor {

    private static final Logger log = LoggerFactory.getLogger(PlaybackScheduleActivationMonitor.class);

    private final PlaybackSyncScheduleRepository scheduleRepository;
    private final ContentAssignmentRepository assignmentRepository;
    private final ContentAssignmentService assignmentService;
    private final DeviceRepository deviceRepository;

    @Value("${app.sync.activation-max-lead-cap:PT15M}")
    private Duration maxLeadCap;

    public PlaybackScheduleActivationMonitor(PlaybackSyncScheduleRepository scheduleRepository,
                                             ContentAssignmentRepository assignmentRepository,
                                             ContentAssignmentService assignmentService,
                                             DeviceRepository deviceRepository) {
        this.scheduleRepository = scheduleRepository;
        this.assignmentRepository = assignmentRepository;
        this.assignmentService = assignmentService;
        this.deviceRepository = deviceRepository;
    }

    @Scheduled(fixedDelayString = "${app.sync.activation-poll-interval:PT1M}", initialDelayString = "PT1M")
    public void scanCutoverReadiness() {
        Instant windowStart = Instant.now().minus(maxLeadCap);
        var recent = scheduleRepository.findByActivateAtAfter(windowStart);
        if (recent.isEmpty()) {
            return;
        }
        for (PlaybackSyncSchedule row : recent) {
            // Each row is reported independently so one bad lookup doesn't sink the batch.
            try {
                report(row);
            } catch (Exception e) {
                log.warn("Cut-over readiness report failed [assignment={}, version={}]: {}",
                        row.getAssignmentId(), row.getVersionNumber(), e.getMessage());
            }
        }
    }

    private void report(PlaybackSyncSchedule row) {
        ContentAssignment assignment = assignmentRepository.findById(row.getAssignmentId()).orElse(null);
        if (assignment == null || assignment.isDeleted() || !assignment.isConfirmed()) {
            return; // assignment retired/cancelled — nothing left to coordinate.
        }
        var deviceIds = assignmentService.listDeviceIdsForTarget(assignment.getTargetType(), assignment.getTargetId());
        if (deviceIds.isEmpty()) {
            return;
        }
        long ready = deviceRepository.countReadyForVersion(deviceIds, row.getContentVersion());
        int total = deviceIds.size();
        Instant activateAt = row.getActivateAt();
        if (Instant.now().isBefore(activateAt)) {
            log.info("Cut-over pending [assignment={}, version={}, activateAt={}] readiness {}/{}",
                    row.getAssignmentId(), row.getVersionNumber(), activateAt, ready, total);
        } else if (ready < total) {
            log.warn("Cut-over live with stragglers [assignment={}, version={}, activateAt={}] readiness {}/{} "
                            + "— laggards join at the live position, never item 0",
                    row.getAssignmentId(), row.getVersionNumber(), activateAt, ready, total);
        }
    }
}
