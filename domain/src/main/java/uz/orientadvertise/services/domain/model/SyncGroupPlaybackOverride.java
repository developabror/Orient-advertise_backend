package uz.orientadvertise.services.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * MUTABLE per-sync-group playback re-anchor, written by an operator "jump the whole group to item N".
 * Overrides the immutable per-version anchor ({@link PlaybackSyncSchedule}) for one sync group while
 * the group stays on the exact {@code (assignmentId, versionNumber, contentVersion)} it was jumped on;
 * a content or version change makes it stale, and it is then ignored (and cleaned up) at {@code /sync}
 * time so the group falls back to the base anchor.
 *
 * <p>One row per sync group ({@code UNIQUE sync_group_id}); a re-jump overwrites it in place — hence
 * {@code updatedAt}, and unlike the immutable {@link PlaybackSyncSchedule} this entity is mutable.
 * {@code anchorEpochMs} is the re-anchored loop T0 = {@code activateAt − slotStart[chosenIndex]}, so
 * that at {@code activateAt} the device's {@code floorMod(now − anchor, loop)} resolves to
 * {@code chosenIndex}. Not Lombok (project convention): hand-written ctor + getters, protected no-arg
 * ctor for JPA.
 */
@Entity
@Table(name = "sync_group_playback_override")
public class SyncGroupPlaybackOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sync_group_id", nullable = false, unique = true)
    private Long syncGroupId;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "content_version", nullable = false, length = 64)
    private String contentVersion;

    @Column(name = "chosen_index", nullable = false)
    private int chosenIndex;

    @Column(name = "anchor_epoch_ms", nullable = false)
    private long anchorEpochMs;

    @Column(name = "activate_at", nullable = false)
    private Instant activateAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SyncGroupPlaybackOverride() {
    }

    public SyncGroupPlaybackOverride(Long syncGroupId, Long assignmentId, int versionNumber,
                                     String contentVersion, int chosenIndex, long anchorEpochMs,
                                     Instant activateAt) {
        this.syncGroupId = syncGroupId;
        this.createdAt = Instant.now();
        applyJump(assignmentId, versionNumber, contentVersion, chosenIndex, anchorEpochMs, activateAt);
    }

    /**
     * Overwrite this row for a (re-)jump: the base {@link PlaybackSyncSchedule} anchor stays untouched;
     * only this per-group override moves. Bumps {@code updatedAt}.
     */
    public void applyJump(Long assignmentId, int versionNumber, String contentVersion,
                          int chosenIndex, long anchorEpochMs, Instant activateAt) {
        this.assignmentId = assignmentId;
        this.versionNumber = versionNumber;
        this.contentVersion = contentVersion;
        this.chosenIndex = chosenIndex;
        this.anchorEpochMs = anchorEpochMs;
        this.activateAt = activateAt;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getSyncGroupId() { return syncGroupId; }
    public Long getAssignmentId() { return assignmentId; }
    public int getVersionNumber() { return versionNumber; }
    public String getContentVersion() { return contentVersion; }
    public int getChosenIndex() { return chosenIndex; }
    public long getAnchorEpochMs() { return anchorEpochMs; }
    public Instant getActivateAt() { return activateAt; }
    public long getActivateAtEpochMs() { return activateAt.toEpochMilli(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
