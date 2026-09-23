package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import uz.orientadvertise.services.common.exception.StorageUnavailableException;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

/**
 * Reclaims the object-storage bytes of deleted content (VG-08).
 *
 * <p>Deleting content only ever stamped {@code deleted_at}. The processed MP4 and its poster were
 * left behind deliberately — the API must not block on a network hop to MinIO — but nothing ever
 * came back for them, so storage only grew. Only the {@code content-raw} bucket had a lifecycle
 * rule ({@code RawBucketLifecycleInstaller}), and it covers source uploads, not the processed
 * objects devices actually download. On a small single-volume host that is the disk filling up
 * until Postgres crash-loops.
 *
 * <p>The sweep is deliberately conservative:
 * <ul>
 *   <li>only files with {@code deleted_at} older than {@code app.storage.deleted-content-grace}
 *       are touched, so an accidental delete still has a window in which the bytes exist;</li>
 *   <li>the keys are cleared <b>after</b> the objects are gone, in one short transaction per file.
 *       A storage outage therefore leaves the row for the next run instead of losing the pointer
 *       to bytes that are still there — the failure mode that would make the leak permanent;</li>
 *   <li>a file that is not deleted is never considered, so an object a live playlist still serves
 *       cannot be removed.</li>
 * </ul>
 *
 * <p>Runs hourly rather than nightly: each pass is bounded by {@code batch-size}, and a bulk delete
 * of a big campaign should free its disk within the hour, not the next morning.
 */
@Service
public class ContentObjectCleanupService {

    private static final Logger log = LoggerFactory.getLogger(ContentObjectCleanupService.class);

    private final ContentFileRepository contentFileRepository;
    private final FileStorageService fileStorageService;
    private final Duration grace;
    private final int batchSize;

    public ContentObjectCleanupService(ContentFileRepository contentFileRepository,
                                       FileStorageService fileStorageService,
                                       @Value("${app.storage.deleted-content-grace:P7D}") Duration grace,
                                       @Value("${app.storage.cleanup-batch-size:200}") int batchSize) {
        this.contentFileRepository = contentFileRepository;
        this.fileStorageService = fileStorageService;
        this.grace = grace;
        this.batchSize = batchSize > 0 ? batchSize : 1;
    }

    @Scheduled(fixedDelayString = "${app.storage.cleanup-interval:PT1H}", initialDelayString = "PT5M")
    public void reclaimDeletedContentObjects() {
        // Storage down is not an error here: there is nothing to reclaim until it is back, and the
        // next pass picks the same rows up. Checking first keeps a MinIO outage from filling the
        // log with one failure per deleted file.
        if (!fileStorageService.isStorageAvailable()) {
            log.debug("Skipping deleted-content sweep — object storage is unavailable");
            return;
        }

        var candidates = contentFileRepository.findDeletedWithStorageObjects(
                Instant.now().minus(grace), PageRequest.of(0, batchSize));
        if (candidates.isEmpty()) {
            return;
        }

        int reclaimed = 0;
        int failed = 0;
        for (ContentFile file : candidates) {
            try {
                reclaim(file.getId(), file.getProcessedStorageKey(), file.getThumbnailStorageKey());
                reclaimed++;
            } catch (StorageUnavailableException e) {
                // The store went away mid-sweep; stop rather than log once per remaining row.
                log.info("Deleted-content sweep stopped early — object storage became unavailable "
                        + "after reclaiming {} file(s)", reclaimed);
                return;
            } catch (Exception e) {
                // One bad key must not strand the rest of the batch; the row keeps its keys and is
                // retried next hour.
                failed++;
                log.warn("Could not reclaim storage for deleted content [id={}]: {}",
                        file.getId(), e.toString());
            }
        }
        log.info("Deleted-content sweep: reclaimed {} file(s){}", reclaimed,
                failed > 0 ? ", " + failed + " left for the next run" : "");
    }

    /**
     * Delete one file's objects, then forget their keys. The storage calls are deliberately OUTSIDE
     * the transaction — this is the same rule as {@code /sync} (VG-07): never hold a pooled
     * connection across a call to MinIO.
     */
    private void reclaim(Long contentFileId, String processedKey, String thumbnailKey) {
        if (processedKey != null) {
            fileStorageService.deleteProcessed(processedKey);
        }
        if (thumbnailKey != null) {
            fileStorageService.deleteThumbnail(thumbnailKey);
        }
        // The repository method carries its own transaction: calling a @Transactional method on
        // THIS class would be a self-invocation and get none at all.
        contentFileRepository.clearStorageKeysOfDeleted(contentFileId, Instant.now());
        log.debug("Reclaimed storage for deleted content [id={}, processed={}, thumbnail={}]",
                contentFileId, processedKey, thumbnailKey);
    }
}
