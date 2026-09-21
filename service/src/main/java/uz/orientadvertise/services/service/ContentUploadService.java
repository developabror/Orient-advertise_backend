package uz.orientadvertise.services.service;

import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.content.ContentUploadedEvent;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.ProjectRepository;
import uz.orientadvertise.services.domain.storage.StorageClient;

@Service
public class ContentUploadService {

    private static final Logger log = LoggerFactory.getLogger(ContentUploadService.class);

    private final StorageClient storageClient;
    private final ContentFileRepository contentFileRepository;
    private final ProjectRepository projectRepository;
    private final ApplicationEventPublisher events;
    private final ContentUploadService self;
    private final String rawBucket;

    /**
     * @param self self-proxy (same idiom as {@code RetentionCleanupService}) so the public entry
     *             point can stay non-transactional while still invoking a {@code @Transactional}
     *             method through the proxy. A plain {@code this.persistUploaded(...)} would bypass
     *             the transaction advice entirely and silently write outside a transaction.
     */
    public ContentUploadService(StorageClient storageClient,
                                 ContentFileRepository contentFileRepository,
                                 ProjectRepository projectRepository,
                                 ApplicationEventPublisher events,
                                 @Lazy ContentUploadService self,
                                 @Value("${app.minio.raw-bucket:content-raw}") String rawBucket) {
        this.storageClient = storageClient;
        this.contentFileRepository = contentFileRepository;
        this.projectRepository = projectRepository;
        this.events = events;
        this.self = self;
        this.rawBucket = rawBucket;
    }

    /**
     * Upload raw bytes to the content-raw MinIO bucket, persist a ContentFile row with
     * status=UPLOADED, and trigger an async transcode. Returns immediately with the new file ID.
     *
     * <p><b>Tolerant of missing/unknown project.</b> If {@code projectId} is null OR points at a
     * project that does not exist, the upload still succeeds and the resulting {@link ContentFile}
     * is persisted with {@code project = null} ("orphan" content). The caller can attach a project
     * later via {@code PATCH /api/content/{id}/project}. This is intentional: the FE may not have
     * the project picker wired up yet, and we'd rather accept the bytes once than ask the operator
     * to re-upload after they create the project.
     *
     * <p>Edge case: interrupted uploads leave incomplete multipart parts in MinIO. Those are swept
     * by MinIO itself ({@code stale_uploads_expiry}, 24 h) — no application job is involved.
     * Completed raw objects are a separate concern, expired by
     * {@code RawBucketLifecycleInstaller}'s lifecycle rule.
     */
    public UploadResult upload(Long projectId, String originalFilename, String contentType,
                                long sizeBytes, InputStream data) {
        return upload(projectId, originalFilename, contentType, sizeBytes, data, false, null);
    }

    public UploadResult upload(Long projectId, String originalFilename, String contentType,
                                long sizeBytes, InputStream data, boolean urgent) {
        return upload(projectId, originalFilename, contentType, sizeBytes, data, urgent, null);
    }

    /**
     * <b>Deliberately NOT {@code @Transactional}.</b> Two reasons, both structural:
     *
     * <ol>
     *   <li>The MinIO PUT is a multi-megabyte network transfer. Holding a Hikari connection and an
     *       open transaction across it pins a pooled resource for the duration of a third party's
     *       latency. Upload first, then open a transaction only for the row insert.</li>
     *   <li>The transcode dispatch must happen <b>after commit</b>. It used to be a direct
     *       {@code transcoder.transcodeAsync(saved.getId())} call ten lines below {@code save()},
     *       inside this transaction — and because {@code ContentFile} is {@code IDENTITY}-generated,
     *       the INSERT had been issued but not committed. The worker read on another connection
     *       under READ COMMITTED, which takes no lock against a foreign uncommitted INSERT, and
     *       whichever finished first decided the outcome. In production the worker won twice out of
     *       two and both files were stuck in {@code UPLOADED} forever. Publishing
     *       {@link ContentUploadedEvent} and letting an AFTER_COMMIT listener dispatch removes the
     *       race by construction rather than by timing.</li>
     * </ol>
     *
     * @param uploadedBy username of the caller — recorded on {@code content_file.uploaded_by} so
     *                   per-operator ownership ({@code owned ∪ granted}) can be enforced later.
     *                   Both the normal and urgent paths flow through this single construction site.
     */
    public UploadResult upload(Long projectId, String originalFilename, String contentType,
                                long sizeBytes, InputStream data, boolean urgent, String uploadedBy) {
        var storageKey = "raw/" + java.util.UUID.randomUUID() + "_" + sanitize(originalFilename);
        // Outside any transaction on purpose — see the javadoc above.
        storageClient.upload(rawBucket, storageKey, data, sizeBytes, contentType);

        return self.persistUploaded(projectId, originalFilename, contentType, sizeBytes, storageKey,
                urgent, uploadedBy);
    }

    /**
     * The database half of an upload: resolve the project, insert the row, and announce it. Short
     * and connection-cheap by design — the bytes are already in MinIO by the time this runs.
     *
     * <p>Public only because Spring's proxy-based transaction advice cannot apply to a non-public
     * method invoked through {@link #self}.
     */
    @Transactional
    public UploadResult persistUploaded(Long projectId, String originalFilename, String contentType,
                                         long sizeBytes, String storageKey, boolean urgent,
                                         String uploadedBy) {
        Project project = null;
        if (projectId != null) {
            project = projectRepository.findById(projectId).orElse(null);
            if (project == null) {
                // Don't 404 — accept the upload as orphan content. The caller can attach
                // a real project later. Logging at WARN so the divergence is auditable.
                log.warn("Upload referenced unknown projectId={} — saving as orphan content", projectId);
            }
        }

        var contentFile = new ContentFile(project, originalFilename, contentType, sizeBytes,
                storageKey, null);
        contentFile.setUploadedBy(uploadedBy);
        var saved = contentFileRepository.save(contentFile);
        log.info("Uploaded content file [id={}, project={}, key={}, size={}, urgent={}]",
                saved.getId(),
                saved.getProject() != null ? saved.getProject().getId() : null,
                storageKey, sizeBytes, urgent);

        // Consumed by ContentUploadedTranscodeListener at AFTER_COMMIT. A rolled-back upload
        // therefore dispatches nothing — there would be no row for the worker to find.
        events.publishEvent(new ContentUploadedEvent(saved.getId(), urgent));

        return new UploadResult(saved.getId(), saved.getStatus().name(), storageKey, urgent,
                saved.getProject() != null ? saved.getProject().getId() : null);
    }

    private static String sanitize(String filename) {
        if (filename == null) return "unnamed";
        return filename.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * @param projectId the resolved project ID; null if the upload was orphan (no/unknown
     *                  project at upload time). The FE uses this to detect orphan rows
     *                  and prompt the operator to bind a project before sending to a device.
     */
    public record UploadResult(Long fileId, String status, String storageKey, boolean urgent,
                                Long projectId) {}
}
