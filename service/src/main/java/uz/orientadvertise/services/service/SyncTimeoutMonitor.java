package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.repository.DeviceRepository;

/**
 * Scans devices in a stuck sync state and escalates to an incident.
 *
 * <p>Edge case: no confirm after 30 minutes of sync state → emit a {@code SYNC_TIMEOUT}
 * event, which {@link IncidentService} converts into an open incident (deduped per device).
 * After escalating, the device's pending markers are cleared so we don't re-emit the
 * same event every minute. Operators close the incident manually; the next /sync call
 * will re-arm the timer if the device is still misbehaving.
 */
@Component
public class SyncTimeoutMonitor {

    private static final Logger log = LoggerFactory.getLogger(SyncTimeoutMonitor.class);
    private static final String EVENT_TYPE = "SYNC_TIMEOUT";

    private final DeviceRepository deviceRepository;
    private final IncidentService incidentService;

    @Value("${app.device.sync-timeout-minutes:30}")
    private long syncTimeoutMinutes;

    public SyncTimeoutMonitor(DeviceRepository deviceRepository, IncidentService incidentService) {
        this.deviceRepository = deviceRepository;
        this.incidentService = incidentService;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void scanForStuckSyncs() {
        Instant threshold = Instant.now().minus(Duration.ofMinutes(syncTimeoutMinutes));
        var stuck = deviceRepository.findBySyncPendingSinceLessThan(threshold);
        if (stuck.isEmpty()) {
            return;
        }
        log.info("Sync timeout scan found {} stuck device(s)", stuck.size());
        for (Device device : stuck) {
            // Each device is escalated in its own transaction so a failure on one
            // doesn't block the rest of the batch.
            try {
                escalate(device.getId());
            } catch (Exception e) {
                log.warn("Sync timeout escalation failed for device {}: {}", device.getId(), e.getMessage());
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void escalate(Long deviceId) {
        var device = deviceRepository.findById(deviceId).orElse(null);
        // A soft-deleted device is out of the operational fleet — never open an incident for
        // it. softDelete() leaves syncPendingSince untouched, so a device deleted mid-sync
        // still surfaces in findBySyncPendingSinceLessThan; this guard is the chokepoint that
        // keeps SYNC_TIMEOUT symmetric with DeviceHealthMonitor.escalate's isDeleted() check.
        if (device == null || device.isDeleted() || device.getSyncPendingSince() == null) {
            return;
        }

        var payload = "{\"pendingSince\":\"%s\",\"expectedVersion\":\"%s\"}".formatted(
                device.getSyncPendingSince(),
                device.getSyncPendingVersion() == null ? "" : device.getSyncPendingVersion());
        var event = new Event(device, EVENT_TYPE, Event.Priority.HIGH, payload, Instant.now());
        incidentService.processEvent(event);

        // Clear the pending markers so we don't re-emit on the next minute. The incident
        // remains open for operator triage; the next /sync call re-arms the timer.
        device.clearSyncPending();

        log.warn("Escalated SYNC_TIMEOUT incident for device {}", deviceId);
    }
}
