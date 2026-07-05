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
@Table(name = "playback_log", uniqueConstraints = {
        @UniqueConstraint(name = "uq_playback_dedup",
                columnNames = {"device_id", "content_file_id", "played_at"})
})
public class PlaybackLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private Device device;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_file_id", nullable = false)
    private ContentFile contentFile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id")
    private ContentAssignment assignment;

    @Column(nullable = false)
    private Instant playedAt;

    @Column
    private Integer durationSeconds;

    @Column(nullable = false)
    private Instant reportedAt;

    protected PlaybackLog() {
    }

    public PlaybackLog(Device device, ContentFile contentFile, ContentAssignment assignment,
                       Instant playedAt, Integer durationSeconds) {
        this.device = device;
        this.contentFile = contentFile;
        this.assignment = assignment;
        this.playedAt = playedAt;
        this.durationSeconds = durationSeconds;
        this.reportedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Device getDevice() { return device; }
    public ContentFile getContentFile() { return contentFile; }
    public ContentAssignment getAssignment() { return assignment; }
    public Instant getPlayedAt() { return playedAt; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public Instant getReportedAt() { return reportedAt; }
}
