package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.DeviceStatusPayload;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceActionType;
import uz.orientadvertise.services.domain.model.DeviceStatusEvaluator;
import uz.orientadvertise.services.domain.model.DeviceVolumeResolver;
import uz.orientadvertise.services.domain.model.Event;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.RemoteActionRepository;

@Service
public class DeviceHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(DeviceHeartbeatService.class);

    private final DeviceRepository deviceRepository;
    private final RemoteActionRepository remoteActionRepository;
    private final ContentAssignmentService contentAssignmentService;
    private final ContentVersionService contentVersionService;
    private final DeviceEventService deviceEventService;
    private final IncidentService incidentService;
    private final DashboardEventBroadcaster dashboardBroadcaster;

    public DeviceHeartbeatService(DeviceRepository deviceRepository,
                                   RemoteActionRepository remoteActionRepository,
                                   ContentAssignmentService contentAssignmentService,
                                   ContentVersionService contentVersionService,
                                   DeviceEventService deviceEventService,
                                   IncidentService incidentService,
                                   DashboardEventBroadcaster dashboardBroadcaster) {
        this.deviceRepository = deviceRepository;
        this.remoteActionRepository = remoteActionRepository;
        this.contentAssignmentService = contentAssignmentService;
        this.contentVersionService = contentVersionService;
        this.deviceEventService = deviceEventService;
        this.incidentService = incidentService;
        this.dashboardBroadcaster = dashboardBroadcaster;
    }

    /**
     * Process a heartbeat from a device. Updates last_heartbeat_at, recalculates
     * status from heartbeat freshness + content state, emits event on status change.
     *
     * Edge cases:
     * - Unknown device ID → ResourceNotFoundException (mapped to 404)
     * - Status derived: > 15min stale (with 60s grace) = OFFLINE, no content = NO_CONTENT, else = ONLINE
     * - Status change always emits an event (non-critical, swallowed on failure)
     */
    @Transactional
    public HeartbeatResult processHeartbeat(Long deviceId) {
        return processHeartbeat(deviceId, null, null);
    }

    @Transactional
    public HeartbeatResult processHeartbeat(Long deviceId, String reportedVersion) {
        return processHeartbeat(deviceId, reportedVersion, null);
    }

    @Transactional
    public HeartbeatResult processHeartbeat(Long deviceId, String reportedVersion, String sourceIp) {
        return processHeartbeat(deviceId, reportedVersion, sourceIp, null);
    }

    /**
     * Heartbeat with optional version + source IP + reported volume. The result's
     * {@code syncRequired} flag is true when the device should fetch new content — either
     * because its reported version doesn't match the server-computed expected version, or
     * because an operator queued a {@code SYNC_CONTENT} action that's still pending. Note
     * the two triggers are kept distinct internally: only the version mismatch anchors the
     * {@code CONTENT_VERSION_MISMATCH} incident timer; a forced sync must not.
     * When {@code sourceIp} is non-null it's persisted on the device for the diagnostics
     * endpoint.
     *
     * <p>{@code reportedVolume} is the device's current output volume (0-100). It is clamped to
     * [0,100] and stored — an out-of-range or {@code null} value never fails the beat (the
     * heartbeat is a liveness signal). The result carries the resolved {@code desiredVolume} the
     * device should converge to (see {@link DeviceVolumeResolver}).
     */
    @Transactional
    public HeartbeatResult processHeartbeat(Long deviceId, String reportedVersion, String sourceIp,
                                            Integer reportedVolume) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        // Normalize a blank reported version to null — both mean "I hold no content yet"
        // (fresh install / data-clear / factory-reset). Keeps the mismatch + incident logic
        // uniform and avoids persisting an empty string as the device's current version.
        if (reportedVersion != null && reportedVersion.isBlank()) {
            reportedVersion = null;
        }

        device.recordHeartbeat();
        // The device reports its current output volume; clamp to [0,100] and store it. An
        // out-of-range or null value must NEVER fail the beat — it's a liveness signal.
        if (reportedVolume != null) {
            int clampedVolume = Math.max(0, Math.min(100, reportedVolume));
            if (clampedVolume != reportedVolume) {
                log.warn("Device {} reported out-of-range volume {} — clamped to {}",
                        deviceId, reportedVolume, clampedVolume);
            }
            device.recordReportedVolume(clampedVolume);
        }
        if (reportedVersion != null) {
            device.setCurrentContentVersion(reportedVersion);
        }
        if (sourceIp != null && !sourceIp.isBlank()) {
            device.setLastKnownIp(sourceIp);
        }

        var previousStatus = device.getStatus();
        var newStatus = deriveStatus(device);

        if (previousStatus != newStatus) {
            device.setStatus(newStatus);
            emitStatusChangeEvent(device, previousStatus, newStatus);
            // Recovery: if device transitioned out of OFFLINE, auto-close any open
            // DEVICE_OFFLINE incident. The auto-resolver itself silently skips manually
            // resolved incidents, so this is safe to call unconditionally.
            if (previousStatus == Device.Status.OFFLINE && newStatus != Device.Status.OFFLINE) {
                tryAutoResolve(deviceId, "DEVICE_OFFLINE");
            }
        }

        var pendingActions = remoteActionRepository.findPendingByDevice(deviceId);

        // Compare reported vs expected; mismatch = device needs to fetch new content.
        // Anchor on EXPECTED, not reported: when the server has content assigned
        // (expectedVersion != null) a device reporting null/blank holds nothing, and
        // null != expected IS a mismatch (spec §4). Gating on reportedVersion instead would
        // tell a reinstalled/wiped device syncRequired=false forever and leave it stuck empty.
        var expectedVersion = contentVersionService.computeExpectedVersion(device, Instant.now());
        boolean mismatch = expectedVersion != null && !expectedVersion.equals(reportedVersion);

        // Anchor (or clear) the mismatch start time so the health monitor can escalate
        // to a WARNING incident if the device stays divergent for >30 minutes. Gate on
        // expectedVersion so a device reporting null while content is assigned — genuinely
        // divergent — also anchors the timer and can later auto-resolve.
        if (expectedVersion != null) {
            boolean wasMismatched = device.getContentMismatchSince() != null;
            device.recordContentMismatch(mismatch);
            // Recovery: device just reported a matching version after previously diverging
            // → close any open CONTENT_VERSION_MISMATCH incident.
            if (wasMismatched && !mismatch) {
                tryAutoResolve(deviceId, "CONTENT_VERSION_MISMATCH");
            }
        }

        // A device must sync when its content is stale (version mismatch) OR when an
        // operator has queued a SYNC_CONTENT action that's still pending. The latter is
        // intentionally excluded from the incident anchoring above — a forced re-sync is
        // not a content divergence.
        boolean pendingSyncContent = pendingActions.stream()
                .anyMatch(a -> DeviceActionType.SYNC_CONTENT.name().equals(a.getActionType()));
        boolean syncRequired = mismatch || pendingSyncContent;

        log.debug("Heartbeat [device={}, status={}, pending={}, expectedVersion={}, reported={}, syncRequired={}]",
                deviceId, newStatus, pendingActions.size(), expectedVersion, reportedVersion, syncRequired);

        int desiredVolume = DeviceVolumeResolver.resolveEffectiveVolume(device);
        String syncGroupId = resolveSyncGroupId(device);
        return new HeartbeatResult(device.getId(), newStatus, pendingActions,
                expectedVersion, syncRequired, desiredVolume, syncGroupId);
    }

    /**
     * Resolve the device's sync group, echoed every beat so a relocated device re-groups promptly
     * (§1.1). Best-effort: a grouping-read failure must never poison the heartbeat — the device just
     * free-runs solo for this cycle and re-tries on the next beat.
     */
    private String resolveSyncGroupId(Device device) {
        try {
            return device.getSyncGroupId();
        } catch (Exception e) {
            log.warn("Sync-group resolution failed [device={}]: {}", device.getId(), e.getMessage());
            return null;
        }
    }

    private Device.Status deriveStatus(Device device) {
        ContentAssignment resolved = contentAssignmentService.resolveForDevice(device, Instant.now());
        boolean hasContent = resolved != null;
        return DeviceStatusEvaluator.evaluate(device.getLastHeartbeatAt(), hasContent, Instant.now());
    }

    private void emitStatusChangeEvent(Device device, Device.Status from, Device.Status to) {
        var payload = "{\"from\":\"%s\",\"to\":\"%s\"}".formatted(from, to);
        var priority = priorityForTransition(from, to);
        deviceEventService.emitAsync(device.getId(), "DEVICE_STATUS_CHANGED", priority, payload);
        log.info("Status change emitted (async): device={} {}→{}", device.getId(), from, to);
        // Dashboard live feed (FE-05/14/29). Best-effort — pub/sub failures must not
        // poison the heartbeat path, so the broadcast is wrapped in its own try/catch.
        try {
            dashboardBroadcaster.deviceStatusChanged(new DeviceStatusPayload(
                    device.getId(), from.name(), to.name(), Instant.now(),
                    device.getRegion() != null && device.getRegion().getProject() != null
                            ? device.getRegion().getProject().getId() : null));
        } catch (Exception e) {
            log.warn("Dashboard status-change broadcast failed [device={}]: {}",
                    device.getId(), e.getMessage());
        }
    }

    private void tryAutoResolve(Long deviceId, String eventType) {
        try {
            incidentService.autoResolveOnRecovery(deviceId, eventType);
        } catch (Exception e) {
            // Recovery handling is best-effort. A DB hiccup must not fail the heartbeat.
            log.warn("Auto-resolve failed [device={}, type={}]: {}", deviceId, eventType, e.getMessage());
        }
    }

    private static Event.Priority priorityForTransition(Device.Status from, Device.Status to) {
        if (to == Device.Status.OFFLINE) return Event.Priority.HIGH;
        if (from == Device.Status.OFFLINE && to == Device.Status.ONLINE) return Event.Priority.MEDIUM;
        return Event.Priority.INFO;
    }

    public record HeartbeatResult(
            Long deviceId,
            Device.Status status,
            List<RemoteAction> pendingActions,
            String expectedContentVersion,
            boolean syncRequired,
            int desiredVolume,
            // Synchronized-playback group (§1.1): facility ?? group ?? region, or null ⇒ free-run solo.
            String syncGroupId
    ) {
        // Backwards-compatible 3-arg constructor for callers that don't track versions/volume.
        public HeartbeatResult(Long deviceId, Device.Status status, List<RemoteAction> pendingActions) {
            this(deviceId, status, pendingActions, null, false, DeviceVolumeResolver.DEFAULT_VOLUME, null);
        }

        // Back-compat 5-arg (pre-volume) constructor — defaults desiredVolume to the resolver default.
        public HeartbeatResult(Long deviceId, Device.Status status, List<RemoteAction> pendingActions,
                               String expectedContentVersion, boolean syncRequired) {
            this(deviceId, status, pendingActions, expectedContentVersion, syncRequired,
                    DeviceVolumeResolver.DEFAULT_VOLUME, null);
        }
    }
}
