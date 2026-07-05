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
@Table(name = "region", uniqueConstraints = {
        @UniqueConstraint(name = "uq_region_code_per_project", columnNames = {"project_id", "code"})
})
public class Region {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Region() {
    }

    public Region(Project project, String name, String code) {
        this.project = project;
        this.name = name;
        this.code = code;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; this.updatedAt = Instant.now(); }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; this.updatedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
