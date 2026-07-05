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
@Table(name = "advertiser_content_access", uniqueConstraints = {
        @UniqueConstraint(name = "uq_adv_access", columnNames = {"user_id", "content_file_id"})
})
public class AdvertiserContentAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_file_id", nullable = false)
    private ContentFile contentFile;

    @Column(nullable = false)
    private Instant grantedAt;

    @Column(nullable = false, length = 100)
    private String grantedBy;

    protected AdvertiserContentAccess() {
    }

    public AdvertiserContentAccess(AppUser user, ContentFile contentFile, String grantedBy) {
        this.user = user;
        this.contentFile = contentFile;
        this.grantedBy = grantedBy;
        this.grantedAt = Instant.now();
    }

    public Long getId() { return id; }
    public AppUser getUser() { return user; }
    public ContentFile getContentFile() { return contentFile; }
    public Instant getGrantedAt() { return grantedAt; }
    public String getGrantedBy() { return grantedBy; }
}
