package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
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
 *
 * <p>Recovery sweep: after escalating, the scan closes every open {@code DEVICE_OFFLINE}
 * incident whose device has beaten again since the threshold. The heartbeat closes these
 * itself; the sweep catches any it missed (a failed resolve, incidents opened before v1.0.140).
 *
 * <p>{@link #escalate} is called through {@code self}, the transactional proxy. A plain
 * {@code this.escalate(...)} bypasses the proxy, so {@code REQUIRES_NEW} is silently ignored:
 * the device comes back detached and every LAZY navigation on it — the critical-incident
 * broadcast's project lookup included — throws {@code LazyInitializationException}.
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
    private final ContentVersionService contentVersionService;
    private final DeviceHealthMonitor self;

    public DeviceHealthMonitor(DeviceRepository deviceRepository,
                                IncidentRepository incidentRepository,
                                IncidentService incidentService,
                                DashboardService dashboardService,
                                DashboardEventBroadcaster dashboardBroadcaster,
                                ContentVersionService contentVersionService,
                                @Lazy DeviceHealthMonitor self) {
        this.deviceRepository = deviceRepository;
        this.incidentRepository = incidentRepository;
        this.incidentService = incidentService;
        this.dashboardService = dashboardService;
        this.dashboardBroadcaster = dashboardBroadcaster;
        this.contentVersionService = contentVersionService;
        this.self = self;
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
                var outcome = self.escalate(d.getId(), EVENT_OFFLINE, Event.Priority.CRITICAL,
                        offlinePayload(d, heartbeatThreshold), now);
                if (outcome.escalated()) {
                    offlineEmitted++;
                    // Derive-only model: the scan never writes device.status. Broadcast the
                    // OFFLINE transition so the live dashboard reflects a silent device without
                    // waiting for a heartbeat — which, by definition, isn't coming. Best-effort,
                    // mirroring DeviceHeartbeatService.emitStatusChangeEvent: a broadcast failure
                    // must not abort the scan. `d` is detached here, so the project comes from
                    // the escalation's transaction, never from d.getRegion().
                    try {
                        dashboardBroadcaster.deviceStatusChanged(new DeviceStatusPayload(
                                d.getId(), d.getStatus() != null ? d.getStatus().name() : null,
                                Device.Status.OFFLINE.name(), now, outcome.projectId()));
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
                if (self.escalate(d.getId(), EVENT_CONTENT_MISMATCH, Event.Priority.MEDIUM,
                        mismatchPayload(d), now).escalated()) {
                    mismatchEmitted++;
                } else {
                    mismatchSkipped++;
                }
            } catch (Exception e) {
                log.warn("Health-check escalation failed for device {} ({}): {}",
                        d.getId(), EVENT_CONTENT_MISMATCH, e.getMessage());
            }
        }

        int offlineRecovered = resolveRecoveredOffline(heartbeatThreshold);
        int mismatchRecovered = resolveMismatchWithNoExpectedContent(now);

        log.info("Health check: offline +{} (skipped {}, recovered {}); mismatch +{} (skipped {}, recovered {})",
                offlineEmitted, offlineSkipped, offlineRecovered, mismatchEmitted, mismatchSkipped,
                mismatchRecovered);

        // Dashboard summary (FE-11/FE-12) is cached for 30s. The counts derive from
        // device_status_view (live — computed from heartbeat age at query time), so the only
        // staleness is the cache itself; evict here so the next read reflects devices that
        // just crossed offline (or whose incident just closed) rather than waiting up to 30s.
        if (offlineEmitted + mismatchEmitted + offlineRecovered + mismatchRecovered > 0) {
            dashboardService.invalidate();
        }

        return new HealthCheckResult(offlineEmitted, offlineSkipped, mismatchEmitted, mismatchSkipped);
    }

    /**
     * Recovery sweep for mismatches the heartbeat path cannot close (VG-15): a device that was
     * diverging when its assignment ended, and has not beaten since, keeps its anchor and its open
     * incident forever. "Recovered" here means the server expects no particular content of it any
     * more, so there is nothing left to diverge from.
     *
     * <p>Only ids are read and each resolve runs in its own transaction, like the offline sweep.
     * Best-effort: a failure is logged and the health check still returns.
     */
    private int resolveMismatchWithNoExpectedContent(Instant now) {
        int recovered = 0;
        try {
            for (Long deviceId : incidentRepository.findDeviceIdsWithOpenIncident(EVENT_CONTENT_MISMATCH)) {
                try {
                    if (self.hasNoExpectedContent(deviceId, now)
                            && incidentService.autoResolveOnRecovery(deviceId, EVENT_CONTENT_MISMATCH)) {
                        recovered++;
                    }
                } catch (Exception e) {
                    log.warn("Mismatch recovery sweep failed for device {}: {}", deviceId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Mismatch recovery sweep failed: {}", e.getMessage());
        }
        return recovered;
    }

    /**
     * True when no assignment resolves for this device any more. Its own transaction (through the
     * {@code self} proxy) because resolving an assignment touches lazy associations, and the sweep
     * above deliberately runs outside one.
     */
    @Transactional(readOnly = true)
    public boolean hasNoExpectedContent(Long deviceId, Instant now) {
        return deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .map(device -> contentVersionService.computeExpectedVersion(device, now) == null)
                .orElse(false);
    }

    /**
     * Recovery sweep: close the open {@code DEVICE_OFFLINE} incidents of devices whose last
     * heartbeat is newer than {@code heartbeatThreshold} — the same instant the escalation
     * compared against, so "stale" and "recovered" can never overlap. Only device ids are
     * read (never Incident entities: their device is LAZY and this runs outside a
     * transaction); each resolve runs in its own transaction on {@link IncidentService}.
     * Best-effort: a failure is logged and the health check still returns.
     */
    private int resolveRecoveredOffline(Instant heartbeatThreshold) {
        int recovered = 0;
        try {
            for (Long deviceId : incidentRepository.findDeviceIdsWithOpenIncidentAndHeartbeatAfter(
                    EVENT_OFFLINE, heartbeatThreshold)) {
                try {
                    if (incidentService.autoResolveOnRecovery(deviceId, EVENT_OFFLINE)) {
                        recovered++;
                    }
                } catch (Exception e) {
                    log.warn("Health-check recovery sweep failed for device {}: {}", deviceId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Health-check recovery sweep failed: {}", e.getMessage());
        }
        return recovered;
    }

    /**
     * Per-device escalation, isolated in its own transaction so a single failure doesn't
     * abort the rest of the scan. Must be called through {@code self} — see the class doc.
     * {@link EscalationOutcome#escalated()} is {@code true} if a new event was emitted,
     * {@code false} if the device was skipped because an open incident already exists OR
     * the condition resolved between the candidate fetch and decision time.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EscalationOutcome escalate(Long deviceId, String eventType, Event.Priority priority,
                                      String payload, Instant now) {
        var device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null || device.isDeleted()) {
            return EscalationOutcome.SKIPPED;
        }
        if (!stillMatches(device, eventType, now)) {
            // Recovery between fetch and now — don't open an incident.
            return EscalationOutcome.SKIPPED;
        }
        if (incidentRepository.existsOpenByDeviceAndEventType(deviceId, eventType)) {
            // Existing open incident — don't bump occurrence on every 5-min tick. An exists check,
            // not the single-result lookup: that one throws on duplicate open incidents (LOGIC-16).
            return EscalationOutcome.SKIPPED;
        }
        var event = new Event(device, eventType, priority, payload, now);
        incidentService.processEvent(event);
        // Read while the device is still managed; the caller only holds a detached copy.
        var project = device.getRegion() != null ? device.getRegion().getProject() : null;
        return new EscalationOutcome(true, project != null ? project.getId() : null);
    }

    /**
     * Result of {@link #escalate}. {@code projectId} is the device's project (the dashboard
     * routing key), read inside the escalation's transaction; {@code null} when skipped.
     */
    public record EscalationOutcome(boolean escalated, Long projectId) {
        static final EscalationOutcome SKIPPED = new EscalationOutcome(false, null);
    }

    /**
     * Re-evaluate the device's current state against the threshold. Protects against the
     * scenario where the device recovered between the candidate fetch and the decision.
     */
    private boolean stillMatches(Device device, String eventType, Instant now) {
        return switch (eventType) {
            case EVENT_OFFLINE -> device.getLastHeartbeatAt() != null
                    && device.getLastHeartbeatAt().isBefore(now.minus(HEARTBEAT_THRESHOLD));
            // VG-15: the anchor alone is not enough. A device that was mismatched when its
            // assignment lapsed keeps the anchor (it may never heartbeat again to clear it), and
            // escalating on it means raising — or re-raising, after an operator resolved it by hand
            // — an incident about content the server no longer expects the device to have.
            case EVENT_CONTENT_MISMATCH -> device.getContentMismatchSince() != null
                    && device.getContentMismatchSince().isBefore(now.minus(CONTENT_MISMATCH_THRESHOLD))
                    && contentVersionService.computeExpectedVersion(device, now) != null;
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
