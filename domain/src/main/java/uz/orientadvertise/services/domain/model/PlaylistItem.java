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

@Entity
@Table(name = "playlist_item", uniqueConstraints = {
        @UniqueConstraint(name = "uq_playlist_position", columnNames = {"playlist_id", "position"})
})
public class PlaylistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playlist_id", nullable = false)
    private Playlist playlist;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_file_id", nullable = false)
    private ContentFile contentFile;

    @Column(nullable = false)
    private int position;

    @Column
    private Integer durationSeconds;

    @Column(nullable = false)
    private Instant createdAt;

    protected PlaylistItem() {
    }

    public PlaylistItem(Playlist playlist, ContentFile contentFile, int position, Integer durationSeconds) {
        this.playlist = playlist;
        this.contentFile = contentFile;
        this.position = position;
        this.durationSeconds = durationSeconds;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Playlist getPlaylist() { return playlist; }
    public ContentFile getContentFile() { return contentFile; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }
    public Instant getCreatedAt() { return createdAt; }
}
