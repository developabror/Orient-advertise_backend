package uz.orientadvertise.services.service;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.model.PlaybackSyncSchedule;
import uz.orientadvertise.services.domain.repository.PlaybackSyncScheduleRepository;

/**
 * Owns the server-anchored playback schedule (Variant A): the immutable coordinated cut-over instant
 * ({@code activateAt}) and loop anchor ({@code anchorEpochMs}) that let a whole sync group flip to a new
 * content version together. Distinct from the campaign dayparting calendar ({@code ScheduleService}) —
 * a different concept — hence the {@code Playback*} prefix.
 *
 * <p><b>Anchor policy (design decisions #4/#5).</b> Exactly one immutable row per
 * {@code (assignmentId, versionNumber)}, created on first activation with
 * {@code activateAt = anchorEpochMs = now + minLead} and never moved thereafter, so the whole group
 * agrees on one T0. Creation is <b>lazy</b>: whichever caller first sees the version — a device's
 * {@code /sync}, or the readiness poller — anchors it; every later caller reads the same row. A version
 * change therefore yields one shared {@code activateAt}; devices download during the lead and flip
 * together, while a straggler that misses it joins at the live position (device-side {@code floorMod}),
 * never at item 0.
 *
 * <p>The cut-over rides the <b>existing</b> {@code SYNC_CONTENT} push → {@code /sync} path (that
 * {@code /sync} now carries {@code activateAt} as an absolute instant), so no separate push message is
 * needed and offline devices converge on their next beat.
 */
@Service
public class PlaybackScheduleService {

    private static final Logger log = LoggerFactory.getLogger(PlaybackScheduleService.class);

    private final PlaybackSyncScheduleRepository scheduleRepository;
    private final PlaybackScheduleWriter writer;
    private final Duration minLead;

    public PlaybackScheduleService(PlaybackSyncScheduleRepository scheduleRepository,
                                   PlaybackScheduleWriter writer,
                                   @Value("${app.sync.activation-min-lead:PT2M}") Duration minLead) {
        this.scheduleRepository = scheduleRepository;
        this.writer = writer;
        this.minLead = minLead;
    }

    /** The immutable anchor row for a content-version, or empty until it is first activated. */
    @Transactional(readOnly = true)
    public Optional<PlaybackSyncSchedule> find(Long assignmentId, int versionNumber) {
        return scheduleRepository.findByAssignmentIdAndVersionNumber(assignmentId, versionNumber);
    }

    /**
     * Return the shared anchor row for {@code (assignmentId, versionNumber)}, creating it on first
     * activation with a coordinated cut-over lead of {@code minLead}. Idempotent and stable: a second
     * call returns the existing row unchanged, so the anchor never moves. The insert is isolated in its
     * own transaction (see {@link PlaybackScheduleWriter}); a lost creation race re-reads the winner's
     * row so the group still shares exactly one anchor.
     */
    public PlaybackSyncSchedule getOrCreate(Long assignmentId, int versionNumber, String contentVersion) {
        return scheduleRepository.findByAssignmentIdAndVersionNumber(assignmentId, versionNumber)
                .orElseGet(() -> createAnchored(assignmentId, versionNumber, contentVersion));
    }

    private PlaybackSyncSchedule createAnchored(Long assignmentId, int versionNumber, String contentVersion) {
        try {
            var row = writer.insertIfAbsent(assignmentId, versionNumber, contentVersion, minLead);
            log.info("Playback schedule anchored [assignment={}, version={}, contentVersion={}, activateAt={}]",
                    assignmentId, versionNumber, contentVersion, row.getActivateAt());
            return row;
        } catch (DataIntegrityViolationException race) {
            // Lost the create race — the winner's row is now committed (the writer's REQUIRES_NEW
            // transaction rolled back, not the caller's). Re-read it so the group shares one anchor.
            return scheduleRepository.findByAssignmentIdAndVersionNumber(assignmentId, versionNumber)
                    .orElseThrow(() -> race);
        }
    }
}
