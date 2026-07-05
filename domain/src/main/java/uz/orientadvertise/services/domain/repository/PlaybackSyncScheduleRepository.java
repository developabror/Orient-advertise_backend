package uz.orientadvertise.services.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.orientadvertise.services.domain.model.PlaybackSyncSchedule;

public interface PlaybackSyncScheduleRepository extends JpaRepository<PlaybackSyncSchedule, Long> {

    /** The single schedule row for an assignment's content-version, or empty until first activation. */
    Optional<PlaybackSyncSchedule> findByAssignmentIdAndVersionNumber(Long assignmentId, int versionNumber);

    /**
     * Recent + upcoming cut-overs for the readiness poll. Since {@code activateAt = createdAt + minLead}
     * (a small fixed lead), passing {@code now - maxLeadCap} bounds the scan to roughly the last cut-over
     * window rather than every schedule ever written.
     */
    List<PlaybackSyncSchedule> findByActivateAtAfter(Instant threshold);
}
