package uz.orientadvertise.services.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * A project-scoped "sales point" grouping for synchronized playback (one sales point = one
 * sync group). Distinct from {@link Region}/{@link Facility}/{@link DeviceGroup}: it exists
 * only to coordinate frame-aligned playback across the devices an operator groups together.
 *
 * <p>Sits at the TOP of {@link Device#getSyncGroupId()}'s derivation chain, so a device in a
 * sync group emits an opaque {@code "sg-{id}"} wire label; the Android app is unaffected.
 *
 * <p><b>No soft delete</b> (no {@code deletedAt}) and <b>no volume</b> — deletion is a HARD
 * delete guarded by a zero-active-member check, and volume stays on {@link DeviceGroup}.
 */
@Entity
@Table(name = "sync_group", uniqueConstraints = {
        @UniqueConstraint(name = "uq_sync_group_name_per_project", columnNames = {"project_id", "name"})
})
public class SyncGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected SyncGroup() {
    }

    public SyncGroup(Project project, String name) {
        this.project = project;
        this.name = name;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; this.updatedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
