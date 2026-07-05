package uz.orientadvertise.services.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "playlist", uniqueConstraints = {
        @UniqueConstraint(name = "uq_playlist_name_per_project", columnNames = {"project_id", "name"})
})
public class Playlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    @OneToMany(mappedBy = "playlist", fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<PlaylistItem> items = new ArrayList<>();

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant deletedAt;

    protected Playlist() {
    }

    public Playlist(Project project, String name, String description) {
        this.project = project;
        this.name = name;
        this.description = description;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; this.updatedAt = Instant.now(); }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; this.updatedAt = Instant.now(); }
    public List<PlaylistItem> getItems() { return items; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public boolean isDeleted() { return deletedAt != null; }

    public void softDelete() {
        this.deletedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markUpdated() {
        this.updatedAt = Instant.now();
    }
}
