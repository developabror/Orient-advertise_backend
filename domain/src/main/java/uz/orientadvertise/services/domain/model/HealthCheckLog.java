package uz.orientadvertise.services.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "health_check_log")
public class HealthCheckLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String component;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private Instant checkedAt;

    @Column(length = 500)
    private String details;

    protected HealthCheckLog() {
    }

    public HealthCheckLog(String component, String status, Instant checkedAt, String details) {
        this.component = component;
        this.status = status;
        this.checkedAt = checkedAt;
        this.details = details;
    }

    public Long getId() {
        return id;
    }

    public String getComponent() {
        return component;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public String getDetails() {
        return details;
    }
}
