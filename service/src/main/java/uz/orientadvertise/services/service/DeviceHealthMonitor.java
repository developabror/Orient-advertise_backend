package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.DeviceStatusPayload;
import uz.orientadvertise.services.domain.health.DeviceHealthChecker;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;

/**
 * Periodically scans devices for two failure conditions and converts them into incidents.
 *
 * <p><b>CRITICAL — heartbeat stale &gt; 15 min</b>: device hasn't checked in within the
 * offline threshold. Emits a {@code DEVICE_OFFLINE} event with {@link Event.Priority#CRITICAL}.
 *
 * <p><b>WARNING — content mismatch &gt; 30 min</b>: device's reported version diverged
 * from server-expected for more than 30 minutes (anchor set/cleared in
 * {@link DeviceHeartbeatService} via {@code Device.recordContentMismatch}). Emits a
 * {@code CONTENT_VERSION_MISMATCH} event with {@link Event.Priority#MEDIUM}.
 *
 * <p>Edge case: re-check incident status before opening, to avoid duplicates on recovery.
 * For each candidate device, the monitor re-reads the open-incident table and skips
 * emission if an open incident already exists for {@code (deviceId, eventType)}. Without
 * this, every 5-minute pass would re-fire and {@link IncidentService#processEvent}
 * would bump the existing incident's occurrence counter forever — even after the device
 * has recovered (between the time the condition cleared and the next scan).
 */
@Service
public class DeviceHealthMonitor implements DeviceHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(DeviceHealthMonitor.class);

    public static final Duration HEARTBEAT_THRESHOLD = Duration.ofMinutes(15);
    public static final Duration CONTENT_MISMATCH_THRESHOLD = Duration.ofMinutes(30);
    public static final String EVENT_OFFLINE = "DEVICE_OFFLINE";
    public static final String EVENT_CONTENT_MISMATCH = "CONTENT_VERSION_MISMATCH";

    private final DeviceRepository deviceRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentService incidentService;
    private final DashboardService dashboardService;
    private final DashboardEventBroadcaster dashboardBroadcaster;

    public DeviceHealthMonitor(DeviceRepository deviceRepository,
                                IncidentRepository incidentRepository,
                                IncidentService incidentService,
                                DashboardService dashboardService,
                                DashboardEventBroadcaster dashboardBroadcaster) {
        this.deviceRepository = deviceRepository;
        this.incidentRepository = incidentRepository;
        this.incidentService = incidentService;
        this.dashboardService = dashboardService;
        this.dashboardBroadcaster = dashboardBroadcaster;
    }

    @Override
    public HealthCheckResult runHealthCheck() {
        Instant now = Instant.now();
        Instant heartbeatThreshold = now.minus(HEARTBEAT_THRESHOLD);
        Instant mismatchThreshold = now.minus(CONTENT_MISMATCH_THRESHOLD);

        List<Device> staleHeartbeats = deviceRepository.findRegisteredWithStaleHeartbeat(heartbeatThreshold);
        List<Device> staleMismatches = deviceRepository.findRegisteredWithStaleContentMismatch(mismatchThreshold);

        int offlineEmitted = 0;
        int offlineSkipped = 0;
        for (Device d : staleHeartbeats) {
            try {
                if (escalate(d.getId(), EVENT_OFFLINE, Event.Priority.CRITICAL,
                        offlinePayload(d, heartbeatThreshold), now)) {
                    offlineEmitted++;
                    // Derive-only model: the scan never writes device.status. Broadcast the
                    // OFFLINE transition so the live dashboard reflects a silent device without
                    // waiting for a heartbeat — which, by definition, isn't coming. Best-effort,
                    // mirroring DeviceHeartbeatService.emitStatusChangeEvent: a broadcast failure
                    // must not abort the scan.
                    try {
                        dashboardBroadcaster.deviceStatusChanged(new DeviceStatusPayload(
                                d.getId(), d.getStatus() != null ? d.getStatus().name() : null,
                                Device.Status.OFFLINE.name(), now,
                                d.getRegion() != null && d.getRegion().getProject() != null
                                        ? d.getRegion().getProject().getId() : null));
                    } catch (Exception be) {
                        log.warn("Dashboard offline-broadcast failed [device={}]: {}",
                                d.getId(), be.getMessage());
                    }
                } else {
                    offlineSkipped++;
                }
            } catch (Exception e) {
                log.warn("Health-check escalation failed for device {} ({}): {}",
                        d.getId(), EVENT_OFFLINE, e.getMessage());
            }
        }

        int mismatchEmitted = 0;
        int mismatchSkipped = 0;
        for (Device d : staleMismatches) {
            try {
                if (escalate(d.getId(), EVENT_CONTENT_MISMATCH, Event.Priority.MEDIUM,
                        mismatchPayload(d), now)) {
                    mismatchEmitted++;
                } else {
                    mismatchSkipped++;
                }
            } catch (Exception e) {
                log.warn("Health-check escalation failed for device {} ({}): {}",
                        d.getId(), EVENT_CONTENT_MISMATCH, e.getMessage());
            }
        }

        log.info("Health check: offline +{} (skipped {}); mismatch +{} (skipped {})",
                offlineEmitted, offlineSkipped, mismatchEmitted, mismatchSkipped);

        // Dashboard summary (FE-11/FE-12) is cached for 30s. The counts derive from
        // device_status_view (live — computed from heartbeat age at query time), so the only
        // staleness is the cache itself; evict here so the next read reflects devices that
        // just crossed offline rather than waiting up to 30s for TTL expiry.
        if (offlineEmitted + mismatchEmitted > 0) {
            dashboardService.invalidate();
        }

        return new HealthCheckResult(offlineEmitted, offlineSkipped, mismatchEmitted, mismatchSkipped);
    }

    /**
     * Per-device escalation, isolated in its own transaction so a single failure doesn't
     * abort the rest of the scan. Returns {@code true} if a new event was emitted,
     * {@code false} if the device was skipped because an open incident already exists OR
     * the condition resolved between the candidate fetch and decision time.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean escalate(Long deviceId, String eventType, Event.Priority priority,
                             String payload, Instant now) {
        var device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null || device.isDeleted()) {
            return false;
        }
        if (!stillMatches(device, eventType, now)) {
            // Recovery between fetch and now — don't open an incident.
            return false;
        }
        if (incidentRepository.findOpenByDeviceAndEventType(deviceId, eventType).isPresent()) {
            // Existing open incident — don't bump occurrence on every 5-min tick.
            return false;
        }
        var event = new Event(device, eventType, priority, payload, now);
        incidentService.processEvent(event);
        return true;
    }

    /**
     * Re-evaluate the device's current state against the threshold. Protects against the
     * scenario where the device recovered between the candidate fetch and the decision.
     */
    private boolean stillMatches(Device device, String eventType, Instant now) {
        return switch (eventType) {
            case EVENT_OFFLINE -> device.getLastHeartbeatAt() != null
                    && device.getLastHeartbeatAt().isBefore(now.minus(HEARTBEAT_THRESHOLD));
            case EVENT_CONTENT_MISMATCH -> device.getContentMismatchSince() != null
                    && device.getContentMismatchSince().isBefore(now.minus(CONTENT_MISMATCH_THRESHOLD));
            default -> false;
        };
    }

    private static String offlinePayload(Device d, Instant threshold) {
        return "{\"lastHeartbeatAt\":\"%s\",\"thresholdAt\":\"%s\"}".formatted(
                d.getLastHeartbeatAt(), threshold);
    }

    private static String mismatchPayload(Device d) {
        return "{\"mismatchSince\":\"%s\",\"currentVersion\":\"%s\"}".formatted(
                d.getContentMismatchSince(),
                d.getCurrentContentVersion() == null ? "" : d.getCurrentContentVersion());
    }

}
