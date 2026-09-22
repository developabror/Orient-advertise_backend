package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.ContentStatusPayload;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

/**
 * Re-drives content files whose transcode never happened, never finished, or failed recoverably.
 *
 * <p><b>Why this exists.</b> Until v1.0.132 a row in {@code UPLOADED} had no owner. The only
 * recovery component queried {@code status='TRANSCODING'} — a state the pipeline never committed —
 * so its predicate was permanently unsatisfiable, no scheduled job touched {@code content_file}, no
 * endpoint could retry, and restarting the application recovered nothing. A single lost async
 * dispatch was therefore permanent, and that is exactly what happened to two production uploads.
 *
 * <p>Three independent cases, each with its own clock:
 * <table border="1">
 *   <caption>Sweep cases</caption>
 *   <tr><th>Case</th><th>Signal</th><th>Meaning</th></tr>
 *   <tr><td>Lost dispatch</td><td>{@code UPLOADED} older than the grace period</td>
 *       <td>The after-commit dispatch never reached the pool (rejected, or a crash in between)</td></tr>
 *   <tr><td>Crashed encode</td><td>{@code TRANSCODING}, started, lease expired</td>
 *       <td>The process was killed mid-ffmpeg</td></tr>
 *   <tr><td>Lost queue entry</td><td>{@code TRANSCODING}, never started, no longer queued here</td>
 *       <td>The queue rejected it, the process restarted, or the task died before starting</td></tr>
 *   <tr><td>Retryable failure</td><td>{@code FAILED} under the attempt cap</td>
 *       <td>ffmpeg errored or hit its timeout; worth another go</td></tr>
 * </table>
 *
 * <p><b>The trap this deliberately avoids:</b> keying on {@code updated_at}. The pipeline stamps
 * {@code TRANSCODING} once and does not touch the row again until the terminal state, so
 * {@code updated_at} does not advance during a 15-minute encode — an {@code updated_at}-keyed
 * sweeper would happily start a second ffmpeg on a perfectly healthy, merely slow job. The lease
 * column exists for precisely this reason, and it is only set when the encode actually starts.
 *
 * <p><b>Queued is not stalled (LOGIC-08).</b> The lease used to be stamped at claim time, so a job
 * waiting behind other encodes on a single-width pool for longer than the lease looked crashed: it
 * was re-claimed (one attempt each time) and, after three rounds, marked FAILED without ever having
 * been encoded. Now a claimed job has no lease until it starts, attempts count only encodes that
 * started, and any row this process still holds (queued or running — {@link
 * TranscodeDispatchService#isPending}) is skipped however old it is.
 *
 * <p>Every dispatch goes through {@link TranscodeDispatchService}, i.e. through the atomic claim, so
 * a sweep racing a live upload cannot double-start an encode. Attempts are capped so a poison file
 * lands in {@code FAILED} with a reason instead of looping forever, and {@code INVALID} is never
 * swept at all — retrying content the container rejected cannot help.
 *
 * <p>An abandoned row is announced on the dashboard live feed (v1.0.134). It is the only terminal
 * transition not written by the transcoder itself, so it was also the only one no operator ever saw
 * arrive.
 */
@Service
public class TranscodeSweeper {

    private static final Logger log = LoggerFactory.getLogger(TranscodeSweeper.class);

    /** Per-case bound on one sweep, so a large backlog is drained over several runs, not in one burst. */
    static final int MAX_CANDIDATES_PER_CASE = 50;

    private final ContentFileRepository contentFileRepository;
    private final TranscodeDispatchService dispatchService;
    private final DashboardEventBroadcaster dashboardBroadcaster;
    private final Duration lostDispatchAfter;
    private final Duration leaseTimeout;
    private final Duration failedRetryAfter;
    private final int maxAttempts;
    private final Duration staleAlertAfter;

    /**
     * @param lostDispatchAfter how long a row may sit in {@code UPLOADED} before it is presumed
     *                          lost. Must comfortably exceed normal queue latency; the cost of
     *                          being wrong is only a redundant claim attempt.
     * @param leaseTimeout      lease lifetime. Must exceed the ffmpeg ceiling
     *                          ({@code app.video.timeout}) plus both MinIO transfers, or a healthy
     *                          long encode would be reclaimed out from under itself.
     * @param failedRetryAfter  cooldown before a FAILED row is retried, so a doomed encode is not
     *                          re-run on every sweep.
     * @param maxAttempts       encodes started per file before it is parked in FAILED for a human
     *                          (queue waits and re-queues cost nothing). A poison file must not
     *                          loop forever.
     * @param staleAlertAfter   backlog age that counts as an alert-worthy stuck upload; shared with
     *                          {@link TranscodeBacklogHealthIndicator} via the same property.
     */
    public TranscodeSweeper(ContentFileRepository contentFileRepository,
                             TranscodeDispatchService dispatchService,
                             DashboardEventBroadcaster dashboardBroadcaster,
                             @Value("${app.video.sweeper.lost-dispatch-after:PT5M}") Duration lostDispatchAfter,
                             @Value("${app.video.sweeper.lease-timeout:PT20M}") Duration leaseTimeout,
                             @Value("${app.video.sweeper.failed-retry-after:PT15M}") Duration failedRetryAfter,
                             @Value("${app.video.sweeper.max-attempts:3}") int maxAttempts,
                             @Value("${app.video.sweeper.stale-alert-after:PT10M}") Duration staleAlertAfter) {
        this.contentFileRepository = contentFileRepository;
        this.dispatchService = dispatchService;
        this.dashboardBroadcaster = dashboardBroadcaster;
        this.lostDispatchAfter = lostDispatchAfter;
        this.leaseTimeout = leaseTimeout;
        this.failedRetryAfter = failedRetryAfter;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.staleAlertAfter = staleAlertAfter;
    }

    @Scheduled(fixedDelayString = "${app.video.sweeper.interval:PT2M}",
                initialDelayString = "${app.video.sweeper.initial-delay:PT1M}")
    public void scheduledSweep() {
        try {
            sweep(Instant.now(), false);
        } catch (Exception e) {
            // Never let a sweep failure kill the scheduler thread's future runs.
            log.warn("Transcode sweep failed (will retry on the next tick): {}", e.getMessage(), e);
        }
    }

    /**
     * Boot recovery. Shares one code path with the periodic sweep — there is no second, subtly
     * different recovery implementation to drift out of sync.
     *
     * <p>Runs in {@code boot} mode, which reclaims <b>every</b> {@code TRANSCODING} row regardless of
     * lease age: a JVM that has just become ready cannot own an in-flight encode, so waiting out the
     * lease would only delay recovery after a crash. (This assumes one application instance per
     * database, which is the deployment model; with several instances the periodic lease-based
     * branch is the one that keeps them from stepping on each other.)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        try {
            SweepResult result = sweep(Instant.now(), true);
            if (result.total() > 0) {
                log.warn("Startup transcode recovery: {}", result);
            } else {
                log.debug("Startup transcode recovery: nothing to re-drive");
            }
        } catch (Exception e) {
            // Schema may be unavailable in some slim test contexts — never block readiness.
            log.warn("Startup transcode recovery skipped (non-critical): {}", e.getMessage());
        }
    }

    /**
     * @param bootMode reclaim every {@code TRANSCODING} row instead of only leases older than
     *                 {@link #leaseTimeout}
     */
    public SweepResult sweep(Instant now, boolean bootMode) {
        var page = PageRequest.of(0, MAX_CANDIDATES_PER_CASE);

        List<ContentFile> lost = contentFileRepository.findLostDispatchCandidates(
                now.minus(lostDispatchAfter), page);
        // In boot mode nothing can be legitimately in flight, so both cutoffs are "now". A claimed
        // job that never started gets the same grace as an UPLOADED row before it counts as lost.
        Instant leaseCutoff = bootMode ? now : now.minus(leaseTimeout);
        Instant queueCutoff = bootMode ? now : now.minus(lostDispatchAfter);
        List<ContentFile> stalled = contentFileRepository.findStalledTranscodeCandidates(
                leaseCutoff, queueCutoff, dispatchService.heldIds(), page);
        List<ContentFile> retryable = contentFileRepository.findRetryableFailedCandidates(
                maxAttempts, now.minus(failedRetryAfter), page);

        int redispatched = 0;
        int abandoned = 0;
        for (var candidate : concat(lost, stalled, retryable)) {
            boolean claimed = candidate.getStatus() == ContentFile.Status.TRANSCODING;
            if (claimed && dispatchService.isPending(candidate.getId())) {
                // Still queued or running in this process — waiting, not lost (LOGIC-08).
                continue;
            }
            if (candidate.getStorageKey() == null) {
                // Nothing to transcode from — re-dispatching would burn attempts on a dead row.
                abandoned += abandon(candidate, "No raw storage key — nothing to transcode", now);
                continue;
            }
            if (candidate.getTranscodeAttempts() >= maxAttempts) {
                abandoned += abandon(candidate,
                        "Abandoned after " + candidate.getTranscodeAttempts() + " transcode attempt(s)", now);
                continue;
            }
            boolean dispatched = claimed
                    ? dispatchService.requeue(candidate.getId(), leaseCutoff, queueCutoff)
                    : dispatchService.dispatch(candidate.getId(), candidate.getStatus(), false);
            if (dispatched) {
                log.warn("Re-driving transcode [id={}, from={}, attempts={}]",
                        candidate.getId(), candidate.getStatus(), candidate.getTranscodeAttempts());
                redispatched++;
            }
        }

        if (!bootMode) {
            // Crashed encodes were just re-queued (their lease is cleared), so whatever is still past
            // its lease is running here: a hung ffmpeg/ffprobe holding a pool slot. The sweeper never
            // reclaims a held file, so this is the only place that notices.
            long pastLease = contentFileRepository.countEncodesPastLease(leaseCutoff);
            if (pastLease > 0) {
                log.error("Transcode: {} encode(s) still running after {} — possibly hung; every job "
                        + "queued behind them waits. Check ffmpeg/ffprobe on the host", pastLease, leaseTimeout);
            }
        }

        long backlog = contentFileRepository.countStaleUploaded(now.minus(staleAlertAfter));
        if (backlog > 0) {
            // WARN+ is forwarded to Telegram by TelegramAppender, so this doubles as the alert that
            // was missing when the incident went unnoticed until a user complained.
            log.warn("Transcode backlog: {} file(s) still UPLOADED after {} — uploads are not being "
                            + "transcoded; check the transcode pool and the ffmpeg binary",
                    backlog, staleAlertAfter);
        }

        return new SweepResult(lost.size(), stalled.size(), retryable.size(), redispatched, abandoned, backlog);
    }

    private int abandon(ContentFile candidate, String reason, Instant now) {
        int rows = contentFileRepository.abandonTranscode(candidate.getId(), candidate.getStatus(), reason, now);
        if (rows == 1) {
            log.error("Transcode abandoned [id={}, status={}]: {}",
                    candidate.getId(), candidate.getStatus(), reason);
            broadcastAbandoned(candidate, reason, now);
        }
        return rows;
    }

    /**
     * Announce the terminal FAILED this sweep just committed.
     *
     * <p>This is the one transition the live feed would otherwise never carry. Every other terminal
     * state is written by {@code FFmpegTranscoder}, which broadcasts; a row abandoned here goes
     * terminal with no pipeline running, so without this an operator's grid keeps showing
     * "Transcoding" until the next poll — for exactly the failure the live feed exists to report.
     *
     * <p>Best-effort, and deliberately after the CAS returned 1: a broadcast must never fail the
     * sweep, and must never announce a state that was not written.
     */
    private void broadcastAbandoned(ContentFile candidate, String reason, Instant now) {
        try {
            dashboardBroadcaster.contentStatusChanged(new ContentStatusPayload(
                    candidate.getId(), ContentFile.Status.FAILED.name(), reason, now,
                    // Not candidate.getProject(): the sweep runs outside any persistence context,
                    // so the LAZY association would throw. uploadedBy is a plain column.
                    contentFileRepository.findProjectIdById(candidate.getId()),
                    candidate.getUploadedBy()));
        } catch (Exception e) {
            log.warn("Content-status broadcast failed [id={}, status=FAILED]: {}",
                    candidate.getId(), e.getMessage());
        }
    }

    @SafeVarargs
    private static List<ContentFile> concat(List<ContentFile>... lists) {
        var all = new java.util.ArrayList<ContentFile>();
        for (var list : lists) {
            all.addAll(list);
        }
        return all;
    }

    /**
     * @param backlog files still in {@code UPLOADED} past the alert threshold — the operator-facing
     *                number, independent of what this particular sweep managed to do about it
     */
    public record SweepResult(int lostDispatch, int stalledTranscode, int retryableFailed,
                              int redispatched, int abandoned, long backlog) {

        public int total() {
            return lostDispatch + stalledTranscode + retryableFailed;
        }

        @Override
        public String toString() {
            return "lostDispatch=%d, stalledTranscode=%d, retryableFailed=%d, redispatched=%d, abandoned=%d, backlog=%d"
                    .formatted(lostDispatch, stalledTranscode, retryableFailed, redispatched, abandoned, backlog);
        }
    }
}
