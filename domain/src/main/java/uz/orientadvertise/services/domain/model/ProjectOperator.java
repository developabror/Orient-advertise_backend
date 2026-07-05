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
 * Assigns an OPERATOR user to a project. An operator's visible scope is the union of the
 * projects they appear under here; an operator with no rows sees an empty hierarchy
 * (fail-closed). Structurally mirrors {@link AdvertiserContentAccess} — an immutable
 * join row with audit fields, getters only.
 */
@Entity
@Table(name = "project_operator", uniqueConstraints = {
        @UniqueConstraint(name = "uq_project_operator", columnNames = {"project_id", "user_id"})
})
public class ProjectOperator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private Instant assignedAt;

    @Column(nullable = false, length = 100)
    private String assignedBy;

    protected ProjectOperator() {
    }

    public ProjectOperator(Project project, AppUser user, String assignedBy) {
        this.project = project;
        this.user = user;
        this.assignedBy = assignedBy;
        this.assignedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public AppUser getUser() { return user; }
    public Instant getAssignedAt() { return assignedAt; }
    public String getAssignedBy() { return assignedBy; }
}
