package uz.orientadvertise.services.infra.schedule;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import uz.orientadvertise.services.domain.health.DeviceHealthChecker;

/**
 * Quartz job that runs the device health check every 5 minutes.
 *
 * <p>{@link DisallowConcurrentExecution} prevents two firings from overlapping. If the
 * scan takes longer than 5 minutes (e.g. large fleet, DB pressure), Quartz will skip the
 * next trigger rather than running concurrently — protecting against double-emission of
 * events.
 */
@DisallowConcurrentExecution
public class DeviceHealthMonitorJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(DeviceHealthMonitorJob.class);

    @Autowired
    private DeviceHealthChecker monitor;

    @Override
    public void execute(JobExecutionContext context) {
        try {
            var result = monitor.runHealthCheck();
            log.debug("Health-check tick: offline +{}/-{}, mismatch +{}/-{}",
                    result.offlineEmitted(), result.offlineSkipped(),
                    result.mismatchEmitted(), result.mismatchSkipped());
        } catch (Exception e) {
            log.warn("Device health check failed (non-critical): {}", e.getMessage());
        }
    }
}
