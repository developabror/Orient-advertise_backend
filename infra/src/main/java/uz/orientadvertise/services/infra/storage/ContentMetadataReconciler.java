package uz.orientadvertise.services.infra.storage;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.common.util.Sha256;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.storage.StorageClient;

/**
 * One-time, self-healing backfill for content transcoded before size/checksum were recorded.
 *
 * <p>Legacy READY files carry the ORIGINAL upload {@code sizeBytes} (not the processed MP4's
 * size) and a null {@code checksum}. The device downloads the processed object and rejects it
 * because the byte count never matches the reported size, and it can't verify integrity. The
 * current transcoder records both at transcode time; this reconciler fixes the historical rows
 * <b>without re-transcoding</b> — it streams the already-stored processed object once to derive
 * its true size and SHA-256, leaving the object, its key, presigned URLs, and content-version
 * hash untouched (so devices don't need to re-download).
 *
 * <p>Mirrors {@link OrphanedTranscodeRecoverer}: runs on {@link ApplicationReadyEvent} (after the
 * app is serving traffic, so it never blocks readiness), is fault-tolerant per file, and is
 * idempotent — the query only matches files with a null checksum, so once backfilled they drop
 * out and subsequent boots are no-ops. Gated by {@code app.content.reconcile-on-startup} so ops
 * can disable it.
 */
@Component
public class ContentMetadataReconciler {

    private static final Logger log = LoggerFactory.getLogger(ContentMetadataReconciler.class);
    // Mirrors FFmpegTranscoder.PROCESSED_BUCKET — the bucket holding the served MP4s.
    private static final String PROCESSED_BUCKET = "content-processed";

    private final ContentFileRepository contentFileRepository;
    private final StorageClient storageClient;
    private final boolean enabled;

    public ContentMetadataReconciler(ContentFileRepository contentFileRepository,
                                      StorageClient storageClient,
                                      @Value("${app.content.reconcile-on-startup:true}") boolean enabled) {
        this.contentFileRepository = contentFileRepository;
        this.storageClient = storageClient;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        if (!enabled) {
            log.debug("Content metadata reconcile disabled (app.content.reconcile-on-startup=false)");
            return;
        }
        if (!storageClient.isAvailable()) {
            log.warn("Content metadata reconcile skipped — storage unavailable");
            return;
        }

        List<ContentFile> stale;
        try {
            stale = contentFileRepository
                    .findByStatusAndChecksumIsNullAndProcessedStorageKeyIsNotNullAndDeletedAtIsNull(
                            ContentFile.Status.READY);
        } catch (Exception e) {
            // Schema may be unavailable in some test contexts — log and skip (non-critical).
            log.warn("Content metadata reconcile skipped (non-critical): {}", e.getMessage());
            return;
        }

        if (stale.isEmpty()) {
            log.debug("No content files need metadata reconciliation");
            return;
        }

        log.warn("Reconciling size+checksum for {} legacy content file(s); each is streamed once — "
                + "set app.content.reconcile-on-startup=false to skip on very large libraries", stale.size());
        int updated = 0;
        for (var file : stale) {
            try {
                if (reconcileOne(file.getId())) {
                    updated++;
                }
            } catch (Exception e) {
                // Best-effort per file: a missing object or storage hiccup must not abort the rest.
                log.warn("Metadata reconcile failed [id={}]: {}", file.getId(), e.getMessage());
            }
        }
        log.info("Content metadata reconcile complete: {}/{} file(s) updated", updated, stale.size());
    }

    /**
     * Reconciles one file in its own transaction (REQUIRES_NEW, mirroring
     * {@link OrphanedTranscodeRecoverer#resetAndRequeue}), so a failure on one file never rolls
     * back others. Re-checks the null-checksum precondition to stay correct if the row changed
     * since the batch query.
     */
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean reconcileOne(Long id) throws java.io.IOException {
        var file = contentFileRepository.findById(id).orElse(null);
        if (file == null || file.getChecksum() != null || file.getProcessedStorageKey() == null) {
            return false;
        }
        var key = file.getProcessedStorageKey();
        // Single pass over the stored object → true size + SHA-256 (bounded memory).
        var result = Sha256.of(storageClient.download(PROCESSED_BUCKET, key));
        if (result.bytes() != file.getSizeBytes()) {
            log.info("Reconcile size [id={}, key={}]: {} -> {}",
                    id, key, file.getSizeBytes(), result.bytes());
            file.setSizeBytes(result.bytes());
        }
        file.setChecksum(result.hex());
        contentFileRepository.save(file);
        return true;
    }
}
