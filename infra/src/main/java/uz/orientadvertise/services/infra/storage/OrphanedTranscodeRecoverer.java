package uz.orientadvertise.services.infra.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.domain.content.Transcoder;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

/**
 * Detects content files left in TRANSCODING state from a previous run
 * (e.g. process killed mid-transcode) and re-enqueues them.
 *
 * Runs once on {@code ApplicationReadyEvent} so the application has finished
 * starting before we start dispatching async work.
 */
@Component
public class OrphanedTranscodeRecoverer {

    private static final Logger log = LoggerFactory.getLogger(OrphanedTranscodeRecoverer.class);

    private final ContentFileRepository contentFileRepository;
    private final Transcoder transcoder;

    public OrphanedTranscodeRecoverer(ContentFileRepository contentFileRepository,
                                       Transcoder transcoder) {
        this.contentFileRepository = contentFileRepository;
        this.transcoder = transcoder;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedTranscodes() {
        java.util.List<ContentFile> orphaned;
        try {
            orphaned = contentFileRepository.findByStatusAndDeletedAtIsNull(ContentFile.Status.TRANSCODING);
        } catch (Exception e) {
            // Schema may be unavailable in some test contexts — log and skip
            log.warn("Orphan recovery skipped (non-critical): {}", e.getMessage());
            return;
        }

        if (orphaned.isEmpty()) {
            log.debug("No orphaned TRANSCODING jobs to recover");
            return;
        }

        log.warn("Found {} orphaned TRANSCODING job(s) — requeuing", orphaned.size());
        for (var file : orphaned) {
            try {
                resetAndRequeue(file);
            } catch (Exception e) {
                log.warn("Failed to requeue orphan [id={}]: {}", file.getId(), e.getMessage());
            }
        }
    }

    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void resetAndRequeue(ContentFile file) {
        file.setStatus(ContentFile.Status.UPLOADED);
        contentFileRepository.save(file);
        log.info("Requeuing orphaned transcode [id={}, name={}]", file.getId(), file.getName());
        transcoder.transcodeAsync(file.getId());
    }
}
