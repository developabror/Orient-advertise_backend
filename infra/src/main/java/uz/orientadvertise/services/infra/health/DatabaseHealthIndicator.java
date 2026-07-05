package uz.orientadvertise.services.infra.health;

import javax.sql.DataSource;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.domain.model.HealthStatus;
import uz.orientadvertise.services.common.util.DateUtils;

@Component
public class DatabaseHealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthIndicator.class);

    private final DataSource dataSource;

    public DatabaseHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public HealthStatus check() {
        try (var conn = dataSource.getConnection()) {
            boolean valid = conn.isValid(2);
            var status = valid
                    ? new HealthStatus.Status.Up()
                    : new HealthStatus.Status.Down("connection invalid");
            return new HealthStatus("database", status, DateUtils.nowIso());
        } catch (SQLException e) {
            // The raw SQLException can carry the JDBC URL, host, driver, or auth detail —
            // never expose it on the unauthenticated /api/health surface. Log it server-side
            // for operators; return a generic reason to the client.
            log.warn("Database health check failed: {}", e.getMessage());
            return new HealthStatus(
                    "database",
                    new HealthStatus.Status.Down("connection error"),
                    DateUtils.nowIso()
            );
        }
    }
}
