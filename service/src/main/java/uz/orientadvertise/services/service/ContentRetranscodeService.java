package uz.orientadvertise.services.service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

/**
 * Operator-triggered retranscode — the manual escape hatch behind
 * {@code POST /api/content/{id}/retranscode}.
 *
 * <p>Before v1.0.132 there was none: a file that missed its transcode could not be re-driven by any
 * endpoint, any actuator surface, or a restart. The automatic {@link TranscodeSweeper} is the
 * primary recovery now, but an operator staring at a stuck row should not have to wait for a timer
 * or file a ticket.
 *
 * <p>Uses the <b>same atomic claim</b> as every other dispatch path, so pressing the button twice,
 * or pressing it exactly as the sweeper picks the row up, starts one encode and not two.
 */
@Service
public class ContentRetranscodeService {

    private static final Logger log = LoggerFactory.getLogger(ContentRetranscodeService.class);

    private final ContentFileRepository contentFileRepository;
    private final TranscodeDispatchService dispatchService;

    public ContentRetranscodeService(ContentFileRepository contentFileRepository,
                                      TranscodeDispatchService dispatchService) {
        this.contentFileRepository = contentFileRepository;
        this.dispatchService = dispatchService;
    }

    /**
     * Re-queue a stuck or failed content file.
     *
     * <p>The attempt counter is reset to zero first, deliberately: a human asking again is not the
     * same event as an automatic retry, and should not be refused because earlier automatic attempts
     * used up the budget.
     *
     * @param requestedBy username for the audit trail
     * @return the row's status after the claim ({@code TRANSCODING})
     * @throws ResourceNotFoundException the row is unknown or soft-deleted (404)
     * @throws IllegalStateException     the row is not retranscodable, or lost the claim race — maps
     *                                   to 409 with a message via {@code GlobalExceptionHandler}
     */
    public RetranscodeResult retranscode(Long contentFileId, String requestedBy) {
        var file = contentFileRepository.findByIdAndDeletedAtIsNull(contentFileId)
                .orElseThrow(() -> new ResourceNotFoundException("ContentFile", contentFileId));

        var status = file.getStatus();
        if (status != ContentFile.Status.UPLOADED && status != ContentFile.Status.FAILED) {
            // READY needs no work; TRANSCODING is already running; INVALID means the container was
            // rejected, and re-running the same bytes through the same ffmpeg cannot change that.
            throw new IllegalStateException(
                    "Content file %d cannot be retranscoded from status %s — only UPLOADED or FAILED files can be retried"
                            .formatted(contentFileId, status));
        }
        if (file.getStorageKey() == null || file.getStorageKey().isBlank()) {
            throw new IllegalStateException(
                    "Content file %d has no raw object to transcode from".formatted(contentFileId));
        }

        contentFileRepository.resetTranscodeAttempts(contentFileId, Instant.now());

        if (!dispatchService.dispatch(contentFileId, status, true)) {
            // Lost the CAS: something else claimed it between our read and the update. Not an error
            // condition for the file — but the caller asked us to start it and we did not, so say so.
            throw new IllegalStateException(
                    "Content file %d was claimed for transcoding by another worker — it is already running"
                            .formatted(contentFileId));
        }

        log.info("Manual retranscode requested [id={}, from={}, by={}]", contentFileId, status, requestedBy);
        return new RetranscodeResult(contentFileId, ContentFile.Status.TRANSCODING.name(), status.name());
    }

    /**
     * @param status         the new status the FE should render immediately
     * @param previousStatus what it was, so the UI can explain what happened
     */
    public record RetranscodeResult(Long fileId, String status, String previousStatus) {}
}
