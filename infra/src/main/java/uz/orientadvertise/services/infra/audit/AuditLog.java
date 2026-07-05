package uz.orientadvertise.services.infra.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String principal;

    @Column(nullable = false, length = 10)
    private String httpMethod;

    @Column(nullable = false, length = 500)
    private String path;

    @Column(nullable = false)
    private int responseStatus;

    @Column(columnDefinition = "TEXT")
    private String requestBody;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Column(nullable = false)
    private Instant timestamp;

    protected AuditLog() {
    }

    public AuditLog(String principal, String httpMethod, String path, int responseStatus,
                    String requestBody, String responseBody, Instant timestamp) {
        this.principal = principal;
        this.httpMethod = httpMethod;
        this.path = path;
        this.responseStatus = responseStatus;
        this.requestBody = requestBody;
        this.responseBody = responseBody;
        this.timestamp = timestamp;
    }

    public Long getId() { return id; }
    public String getPrincipal() { return principal; }
    public String getHttpMethod() { return httpMethod; }
    public String getPath() { return path; }
    public int getResponseStatus() { return responseStatus; }
    public String getRequestBody() { return requestBody; }
    public String getResponseBody() { return responseBody; }
    public Instant getTimestamp() { return timestamp; }
}
