package uz.orientadvertise.services.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "content_file")
public class ContentFile {

    public enum Status { UPLOADED, TRANSCODING, READY, FAILED, INVALID }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 500)
    private String storageKey;

    @Column(name = "processed_storage_key", length = 500)
    private String processedStorageKey;

    @Column(name = "thumbnail_storage_key", length = 500)
    private String thumbnailStorageKey;

    @Column(length = 64)
    private String checksum;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "invalid_reason", length = 500)
    private String invalidReason;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant deletedAt;

    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    protected ContentFile() {
    }

    public ContentFile(Project project, String name, String contentType, long sizeBytes,
                       String storageKey, String checksum) {
        this.project = project;
        this.name = name;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.checksum = checksum;
        this.status = Status.UPLOADED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; this.updatedAt = Instant.now(); }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; this.updatedAt = Instant.now(); }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    /**
     * Overwrites the stored size. Set at ingest to the original upload size, then updated
     * by the transcoder to the PROCESSED object's size — the bytes a device actually
     * downloads from the presigned processed-bucket URL. Keeping it at the original size
     * makes the device's downloaded-bytes check never match, so it rejects the file.
     */
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; this.updatedAt = Instant.now(); }
    public String getStorageKey() { return storageKey; }
    public String getProcessedStorageKey() { return processedStorageKey; }
    public void setProcessedStorageKey(String processedStorageKey) {
        this.processedStorageKey = processedStorageKey;
        this.updatedAt = Instant.now();
    }
    public String getThumbnailStorageKey() { return thumbnailStorageKey; }
    public void setThumbnailStorageKey(String thumbnailStorageKey) {
        this.thumbnailStorageKey = thumbnailStorageKey;
        this.updatedAt = Instant.now();
    }
    public String getChecksum() { return checksum; }
    /**
     * Sets the integrity checksum — the SHA-256 (hex) of the PROCESSED object the device
     * downloads. Null until the transcoder records it (or the metadata reconciler backfills it
     * for legacy files); devices use it for cryptographic integrity verification.
     */
    public void setChecksum(String checksum) { this.checksum = checksum; this.updatedAt = Instant.now(); }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; this.updatedAt = Instant.now(); }
    public String getInvalidReason() { return invalidReason; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
        this.updatedAt = Instant.now();
    }

    public void markInvalid(String reason) {
        this.status = Status.INVALID;
        this.invalidReason = reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public boolean isDeleted() { return deletedAt != null; }

    /** Username that uploaded this file; null for legacy/system rows. Drives per-operator ownership. */
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }

    public void softDelete() {
        this.deletedAt = Instant.now();
        this.updatedAt = Instant.now();
    }
}
