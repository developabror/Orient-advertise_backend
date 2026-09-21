package uz.orientadvertise.services.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.orientadvertise.services.domain.content.ContentUploadedEvent;
import uz.orientadvertise.services.domain.model.ContentFile;

/**
 * Starts the transcode for a freshly uploaded file — <b>after the upload transaction commits</b>.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT} is the entire point, and it is the same idiom
 * {@code AssignmentConfirmedSyncPushListener} already uses. Dispatching from inside the upload
 * transaction is the v1.0.132 incident: the row's INSERT had been issued (IDENTITY id) but not
 * committed, the worker read it on a different connection under READ COMMITTED, found nothing, and
 * the file sat in {@code UPLOADED} forever with a single WARN as the only trace. Two production
 * uploads were lost that way.
 *
 * <p>The corollary is equally load-bearing: a <b>rolled-back</b> upload dispatches nothing, because
 * an AFTER_COMMIT listener never fires for a rollback. Previously a rollback could still leave a
 * queued transcode chasing a row that no longer existed.
 */
@Component
public class ContentUploadedTranscodeListener {

    private static final Logger log = LoggerFactory.getLogger(ContentUploadedTranscodeListener.class);

    private final TranscodeDispatchService dispatchService;

    public ContentUploadedTranscodeListener(TranscodeDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onContentUploaded(ContentUploadedEvent event) {
        if (event == null || event.contentFileId() == null) {
            return;
        }
        boolean dispatched = dispatchService.dispatch(
                event.contentFileId(), ContentFile.Status.UPLOADED, event.urgent());
        if (!dispatched) {
            // Not an error: a sweeper or an operator retry may legitimately have claimed it first.
            // Either way the row is owned by someone, so it cannot go missing.
            log.info("Upload dispatch skipped [id={}] — already claimed for transcoding",
                    event.contentFileId());
        }
    }
}
