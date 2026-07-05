package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.model.PlaybackSyncSchedule;
import uz.orientadvertise.services.domain.repository.PlaybackSyncScheduleRepository;

/**
 * Isolated writer for the playback-schedule anchor. Deliberately a SEPARATE bean from
 * {@link PlaybackScheduleService} so the {@code REQUIRES_NEW} boundary is honored through the Spring
 * proxy — a self-invocation would silently bypass it (a known trap in this codebase). Running the
 * insert in its own transaction has two payoffs: a lost UNIQUE(assignment_id, version_number) race
 * cannot poison the caller's (read-heavy) /sync transaction, and the row can be written even when the
 * caller is executing read-only.
 */
@Component
public class PlaybackScheduleWriter {

    private final PlaybackSyncScheduleRepository scheduleRepository;

    public PlaybackScheduleWriter(PlaybackSyncScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlaybackSyncSchedule insertIfAbsent(Long assignmentId, int versionNumber,
                                               String contentVersion, Duration minLead) {
        // Re-check inside the fresh transaction to collapse the check-then-act window; the UNIQUE
        // (assignment_id, version_number) constraint is the ultimate arbiter if two writers still race.
        return scheduleRepository.findByAssignmentIdAndVersionNumber(assignmentId, versionNumber)
                .orElseGet(() -> scheduleRepository.save(new PlaybackSyncSchedule(
                        assignmentId, versionNumber, contentVersion, Instant.now().plus(minLead))));
    }
}
