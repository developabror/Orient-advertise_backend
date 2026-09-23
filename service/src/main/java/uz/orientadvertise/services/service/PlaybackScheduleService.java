package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * {@code (assignmentId, contentVersion)}, created on first activation and never moved thereafter, so
 * the whole group agrees on one T0. Creation is <b>lazy</b>: whichever caller first sees the version —
 * a device's {@code /sync}, or the readiness poller — anchors it; every later caller reads the same row.
 *
 * <p><b>Keyed on the content version since V53 (VG-06).</b> It used to be keyed on
 * {@code versionNumber}, which {@code ContentAssignment.bumpVersion()} never increments, so a playlist
 * edit reused the ORIGINAL anchor — whose {@code activateAt} was long past, so every screen cut over
 * the moment it finished downloading and a multi-screen site drifted apart until the slowest download
 * landed. The content hash changes with the edit, so an edit now gets a fresh anchor in the future and
 * the group flips together.
 *
 * <p><b>Lead time.</b> {@code activateAt = now + lead}, where the lead is the download time this
 * cut-over actually needs — {@code 1.5 × newBytes / assumed-bytes-per-sec} — clamped to
 * {@code [activation-min-lead, activation-max-lead-cap]}. A reorder or dwell edit downloads nothing and
 * gets the minimum; a cut-over that adds a 200 MB clip gets minutes instead of a deadline it cannot
 * meet. The cap equals the readiness monitor's look-back window, so a pending cut-over is always
 * inside the window it reports on.
 *
 * <p>The cut-over rides the <b>existing</b> {@code SYNC_CONTENT} push → {@code /sync} path (that
 * {@code /sync} now carries {@code activateAt} as an absolute instant), so no separate push message is
 * needed and offline devices converge on their next beat.
 */
@Service
public class PlaybackScheduleService {

    private static final Logger log = LoggerFactory.getLogger(PlaybackScheduleService.class);

    /** Headroom over the ideal transfer time: real links are lossy and devices download serially. */
    private static final double DOWNLOAD_SAFETY_FACTOR = 1.5;

    private final PlaybackSyncScheduleRepository scheduleRepository;
    private final Duration minLead;
    private final Duration maxLeadCap;
    private final long assumedBytesPerSec;

    public PlaybackScheduleService(PlaybackSyncScheduleRepository scheduleRepository,
                                   @Value("${app.sync.activation-min-lead:PT2M}") Duration minLead,
                                   @Value("${app.sync.activation-max-lead-cap:PT15M}") Duration maxLeadCap,
                                   @Value("${app.sync.activation-assumed-bytes-per-sec:1048576}") long assumedBytesPerSec) {
        this.scheduleRepository = scheduleRepository;
        this.minLead = minLead;
        this.maxLeadCap = maxLeadCap;
        this.assumedBytesPerSec = assumedBytesPerSec > 0 ? assumedBytesPerSec : 1L;
    }

    /** The immutable anchor row for a content-version, or empty until it is first activated. */
    @Transactional(readOnly = true)
    public Optional<PlaybackSyncSchedule> find(Long assignmentId, String contentVersion) {
        return scheduleRepository.findByAssignmentIdAndContentVersion(assignmentId, contentVersion);
    }

    /** Whether this assignment is anchored at all — i.e. its devices play to a shared clock. */
    @Transactional(readOnly = true)
    public boolean isAnchored(Long assignmentId) {
        return scheduleRepository.existsByAssignmentId(assignmentId);
    }

    /**
     * Return the shared anchor row for {@code (assignmentId, contentVersion)}, creating it on first
     * activation. Idempotent and stable: a second call returns the existing row unchanged, so the
     * anchor never moves for that content.
     *
     * <p>Runs in the CALLER's transaction and must therefore be called from a write one. The insert
     * is {@code ON CONFLICT DO NOTHING} rather than a {@code REQUIRES_NEW} writer bean: that bean
     * needed a second pooled connection while {@code /sync} still held the first, which is how 20
     * devices taking a new campaign together could exhaust the pool (VG-07). A lost race inserts 0
     * rows and we re-read the winner's, so the group still shares exactly one anchor.
     *
     * @param newBytes total size of the files this cut-over has to download first; 0 for an edit
     *                 that adds nothing (reorder, dwell change) or when it cannot be known.
     */
    public PlaybackSyncSchedule getOrCreate(Long assignmentId, int versionNumber, String contentVersion,
                                            long newBytes) {
        var existing = scheduleRepository.findByAssignmentIdAndContentVersion(assignmentId, contentVersion);
        if (existing.isPresent()) {
            return existing.get();
        }

        Duration lead = leadFor(newBytes);
        Instant activateAt = Instant.now().plus(lead);
        int inserted = scheduleRepository.insertIfAbsent(assignmentId, versionNumber, contentVersion,
                activateAt.toEpochMilli(), activateAt, Instant.now());
        if (inserted > 0) {
            log.info("Playback schedule anchored [assignment={}, version={}, contentVersion={}, "
                            + "activateAt={}, lead={}s, newBytes={}]",
                    assignmentId, versionNumber, contentVersion, activateAt, lead.toSeconds(), newBytes);
        }
        // Whether we won or lost the race, the committed row is the group's one anchor.
        return scheduleRepository.findByAssignmentIdAndContentVersion(assignmentId, contentVersion)
                .orElse(null);
    }

    /**
     * How far ahead to put the cut-over: enough time to pull {@code newBytes}, but never less than
     * the configured minimum (which also covers a no-download edit) or more than the cap.
     */
    Duration leadFor(long newBytes) {
        if (newBytes <= 0) {
            return minLead;
        }
        long seconds = (long) Math.ceil(DOWNLOAD_SAFETY_FACTOR * newBytes / assumedBytesPerSec);
        Duration needed = Duration.ofSeconds(seconds);
        if (needed.compareTo(minLead) < 0) return minLead;
        if (needed.compareTo(maxLeadCap) > 0) return maxLeadCap;
        return needed;
    }
}
