package uz.orientadvertise.services.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Server-anchored playback schedule (Variant A) — one immutable row per assignment content-version.
 *
 * <p>Devices compute "what is on screen right now" as a pure function of synced time from a shared
 * anchor + slot timings. Everything except {@code anchorEpochMs}/{@code activateAt} is derivable from
 * the playlist, so only those are stored — <b>once</b> per {@code (assignmentId, versionNumber)}, and
 * they never move for that version's life. That immutability is what makes the loop identical across a
 * group and stable per {@code contentVersion}.
 *
 * <p>By construction {@code anchorEpochMs == activateAt} (the loop's T0 is the coordinated cut-over
 * instant); the ctor derives the epoch-ms form so the two can never disagree. No soft-delete — the row
 * is tiny and immutable. Not Lombok (project convention): hand-written ctors + getters, no setters.
 */
@Entity
@Table(name = "playback_sync_schedule")
public class PlaybackSyncSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "content_version", nullable = false, length = 64)
    private String contentVersion;

    @Column(name = "anchor_epoch_ms", nullable = false)
    private long anchorEpochMs;

    @Column(name = "activate_at", nullable = false)
    private Instant activateAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PlaybackSyncSchedule() {
    }

    public PlaybackSyncSchedule(Long assignmentId, int versionNumber, String contentVersion, Instant activateAt) {
        this.assignmentId = assignmentId;
        this.versionNumber = versionNumber;
        this.contentVersion = contentVersion;
        this.activateAt = activateAt;
        // anchor == activate: the loop is defined to start at the coordinated cut-over instant.
        this.anchorEpochMs = activateAt.toEpochMilli();
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getAssignmentId() { return assignmentId; }
    public int getVersionNumber() { return versionNumber; }
    public String getContentVersion() { return contentVersion; }
    public long getAnchorEpochMs() { return anchorEpochMs; }
    public Instant getActivateAt() { return activateAt; }
    public long getActivateAtEpochMs() { return activateAt.toEpochMilli(); }
    public Instant getCreatedAt() { return createdAt; }
}
