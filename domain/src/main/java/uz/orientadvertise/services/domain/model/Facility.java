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
@Table(name = "facility", uniqueConstraints = {
        @UniqueConstraint(name = "uq_facility_name_per_region", columnNames = {"region_id", "name"})
})
public class Facility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", nullable = false)
    private Region region;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Facility() {
    }

    public Facility(Region region, String name, String address) {
        this.region = region;
        this.name = name;
        this.address = address;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Region getRegion() { return region; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; this.updatedAt = Instant.now(); }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; this.updatedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
