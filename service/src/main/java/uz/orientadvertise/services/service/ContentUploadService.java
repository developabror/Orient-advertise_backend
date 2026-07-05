package uz.orientadvertise.services.service;

import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.content.Transcoder;
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
    private final Transcoder transcoder;
    private final String rawBucket;

    public ContentUploadService(StorageClient storageClient,
                                 ContentFileRepository contentFileRepository,
                                 ProjectRepository projectRepository,
                                 Transcoder transcoder,
                                 @Value("${app.minio.raw-bucket:content-raw}") String rawBucket) {
        this.storageClient = storageClient;
        this.contentFileRepository = contentFileRepository;
        this.projectRepository = projectRepository;
        this.transcoder = transcoder;
        this.rawBucket = rawBucket;
    }

    /**
     * Upload raw bytes to the content-raw MinIO bucket, persist a ContentFile
     * row with status=UPLOADED, and trigger an async transcode. Returns immediately
     * with the new file ID.
     *
     * <p><b>Tolerant of missing/unknown project.</b> If {@code projectId} is null OR
     * points at a project that does not exist, the upload still succeeds and the
     * resulting {@link ContentFile} is persisted with {@code project = null}
     * ("orphan" content). The caller can attach a project later via
     * {@code PATCH /api/content/{id}/project}. This is intentional: the FE may not
     * have the project picker wired up yet, and we'd rather accept the bytes once
     * than ask the operator to re-upload after they create the project.
     *
     * <p>Edge case: interrupted uploads leave incomplete multipart parts in MinIO.
     * Those are swept by {@code OrphanedUploadCleaner} after 24h.
     */
    @Transactional
    public UploadResult upload(Long projectId, String originalFilename, String contentType,
                                long sizeBytes, InputStream data) {
        return upload(projectId, originalFilename, contentType, sizeBytes, data, false, null);
    }

    @Transactional
    public UploadResult upload(Long projectId, String originalFilename, String contentType,
                                long sizeBytes, InputStream data, boolean urgent) {
        return upload(projectId, originalFilename, contentType, sizeBytes, data, urgent, null);
    }

    /**
     * @param uploadedBy username of the caller — recorded on {@code content_file.uploaded_by} so
     *                   per-operator ownership ({@code owned ∪ granted}) can be enforced later.
     *                   Both the normal and urgent paths flow through this single construction site.
     */
    @Transactional
    public UploadResult upload(Long projectId, String originalFilename, String contentType,
                                long sizeBytes, InputStream data, boolean urgent, String uploadedBy) {
        Project project = null;
        if (projectId != null) {
            project = projectRepository.findById(projectId).orElse(null);
            if (project == null) {
                // Don't 404 — accept the upload as orphan content. The caller can attach
                // a real project later. Logging at WARN so the divergence is auditable.
                log.warn("Upload referenced unknown projectId={} — saving as orphan content", projectId);
            }
        }

        var storageKey = "raw/" + java.util.UUID.randomUUID() + "_" + sanitize(originalFilename);
        storageClient.upload(rawBucket, storageKey, data, sizeBytes, contentType);

        var contentFile = new ContentFile(project, originalFilename, contentType, sizeBytes,
                storageKey, null);
        contentFile.setUploadedBy(uploadedBy);
        var saved = contentFileRepository.save(contentFile);
        log.info("Uploaded content file [id={}, project={}, key={}, size={}, urgent={}]",
                saved.getId(),
                saved.getProject() != null ? saved.getProject().getId() : null,
                storageKey, sizeBytes, urgent);

        // Fire-and-forget — transcoder is @Async, returns immediately
        if (urgent) {
            transcoder.transcodeAsyncUrgent(saved.getId());
        } else {
            transcoder.transcodeAsync(saved.getId());
        }

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
