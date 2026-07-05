package uz.orientadvertise.services.domain.health;

/**
 * Domain port for periodic device-health scanning. Implemented in the service module
 * (so the infra Quartz job can depend on the abstraction without violating layering).
 */
public interface DeviceHealthChecker {

    HealthCheckResult runHealthCheck();

    record HealthCheckResult(int offlineEmitted, int offlineSkipped,
                              int mismatchEmitted, int mismatchSkipped) {}
}
