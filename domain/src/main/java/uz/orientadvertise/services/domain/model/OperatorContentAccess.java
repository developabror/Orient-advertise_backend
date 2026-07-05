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
 * Admin-granted content access for an OPERATOR — the per-operator analogue of
 * {@link AdvertiserContentAccess}, kept as a deliberate structural clone (NOT generalised into
 * one shared table). An operator sees a content file iff they uploaded it
 * ({@code content_file.uploaded_by}) OR an admin granted it via a row here.
 */
@Entity
@Table(name = "operator_content_access", uniqueConstraints = {
        @UniqueConstraint(name = "uq_op_content_access", columnNames = {"user_id", "content_file_id"})
})
public class OperatorContentAccess {

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

    protected OperatorContentAccess() {
    }

    public OperatorContentAccess(AppUser user, ContentFile contentFile, String grantedBy) {
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
