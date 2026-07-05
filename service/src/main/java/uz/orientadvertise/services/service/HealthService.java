package uz.orientadvertise.services.service;

import java.util.List;

import org.springframework.stereotype.Service;
import uz.orientadvertise.services.common.util.DateUtils;
import uz.orientadvertise.services.domain.model.HealthStatus;
import uz.orientadvertise.services.infra.health.DatabaseHealthIndicator;

@Service
public class HealthService {

    private final DatabaseHealthIndicator databaseHealthIndicator;

    public HealthService(DatabaseHealthIndicator databaseHealthIndicator) {
        this.databaseHealthIndicator = databaseHealthIndicator;
    }

    public List<HealthStatus> checkAll() {
        var appStatus = new HealthStatus(
                "application",
                new HealthStatus.Status.Up(),
                DateUtils.nowIso()
        );
        var dbStatus = databaseHealthIndicator.check();
        return List.of(appStatus, dbStatus);
    }

    public HealthStatus checkApplication() {
        return new HealthStatus("application", new HealthStatus.Status.Up(), DateUtils.nowIso());
    }
}
