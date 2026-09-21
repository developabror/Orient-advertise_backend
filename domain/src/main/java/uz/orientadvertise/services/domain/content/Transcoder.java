package uz.orientadvertise.services.domain.content;

/**
 * Port for kicking off out-of-band video transcoding.
 *
 * <h2>Dispatch contract — both methods</h2>
 * <ol>
 *   <li>The caller must <b>already hold the claim</b> on the row, won with
 *       {@code ContentFileRepository.claimForTranscode(...)} returning 1. The implementation
 *       re-checks that claim and logs-and-returns if it is gone, so calling without one is a
 *       silent no-op, not an encode.</li>
 *   <li>The caller must dispatch <b>after its transaction commits</b> —
 *       {@code @TransactionalEventListener(phase = AFTER_COMMIT)} or an equivalent. Dispatching
 *       from inside the transaction that created the row is the v1.0.132 incident: the worker
 *       reads on another connection under READ COMMITTED, finds nothing, and the file is stuck in
 *       {@code UPLOADED} forever. See {@link ContentUploadedEvent}.</li>
 * </ol>
 * Both rules exist because the failure they prevent is silent. {@code TranscodeSweeper} is the
 * safety net, not the primary path.
 */
public interface Transcoder {

    /**
     * Queue asynchronous transcoding of a claimed file. Returns immediately; the work runs on the
     * shared transcode pool, whose width is planned from the host's CPU and memory budget.
     */
    void transcodeAsync(Long contentFileId);

    /**
     * Front-of-queue variant for urgent uploads. Same end state as the regular pipeline — the only
     * difference is scheduling priority: an urgent job overtakes everything queued behind it.
     *
     * <p>It cannot preempt an encode that is already running (nothing short of killing a live
     * ffmpeg could), so on a host whose planned concurrency is 1, "urgent" means "next", not "now".
     */
    void transcodeAsyncUrgent(Long contentFileId);
}
