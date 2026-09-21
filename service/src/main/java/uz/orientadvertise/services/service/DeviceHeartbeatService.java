package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.ArrayList;
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
    /** Width of {@code device.last_known_ip} (V27). */
    private static final int LAST_KNOWN_IP_MAX = 45;

    /** Allowed values for the device's reported input mechanism; anything else is dropped. */
    private static final List<String> REMOTE_INPUT_VALUES = List.of("ROOT", "ACCESSIBILITY", "NONE");
    /** Allowed values for the device's reported media transport; anything else is dropped. */
    private static final List<String> REMOTE_TRANSPORT_VALUES = List.of("SCRCPY_WS", "NONE");

    private final DeviceRepository deviceRepository;
    private final RemoteActionRepository remoteActionRepository;
    private final ContentAssignmentService contentAssignmentService;
    private final ContentVersionService contentVersionService;
    private final DeviceEventService deviceEventService;
    private final IncidentService incidentService;
    private final DashboardEventBroadcaster dashboardBroadcaster;
    private final RemoteSessionService remoteSessionService;

    public DeviceHeartbeatService(DeviceRepository deviceRepository,
                                   RemoteActionRepository remoteActionRepository,
                                   ContentAssignmentService contentAssignmentService,
                                   ContentVersionService contentVersionService,
                                   DeviceEventService deviceEventService,
                                   IncidentService incidentService,
                                   DashboardEventBroadcaster dashboardBroadcaster,
                                   RemoteSessionService remoteSessionService) {
        this.deviceRepository = deviceRepository;
        this.remoteActionRepository = remoteActionRepository;
        this.contentAssignmentService = contentAssignmentService;
        this.contentVersionService = contentVersionService;
        this.deviceEventService = deviceEventService;
        this.incidentService = incidentService;
        this.dashboardBroadcaster = dashboardBroadcaster;
        this.remoteSessionService = remoteSessionService;
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
        return processHeartbeat(deviceId, reportedVersion, sourceIp, reportedVolume, null);
    }

    /**
     * Heartbeat with the optional device-reported remote-control capability block on top of
     * everything the 4-arg overload does.
     *
     * <p>{@code remote} follows the {@code reportedVolume} contract precisely: the device reports
     * what it <i>is</i>, the response says what it <i>should be</i>. A <b>missing or malformed
     * {@code remote} block must never fail the beat</b> — unknown enum values and out-of-range
     * dimensions are dropped, a WARN is logged, and the beat carries on. That is the same rule
     * that already governs {@code volume}, and it is why the whole feature degrades gracefully:
     * a device with a buggy capability reporter still stays online and still syncs content.
     *
     * <p>The result's {@code desiredRemoteSession} is the convergence target — {@code null} means
     * "no session wanted; stop any running one".
     *
     * <p>Incidents the beat recovered from are NOT closed here: the caller passes the result to
     * {@link #resolveRecoveredIncidents} after this method returns (and has committed).
     */
    @Transactional
    public HeartbeatResult processHeartbeat(Long deviceId, String reportedVersion, String sourceIp,
                                            Integer reportedVolume, RemoteCapabilityReport remote) {
        var device = deviceRepository.findByIdAndDeletedAtIsNull(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device", deviceId));

        // Normalize a blank reported version to null — both mean "I hold no content yet"
        // (fresh install / data-clear / factory-reset). Keeps the mismatch + incident logic
        // uniform and avoids persisting an empty string as the device's current version.
        if (reportedVersion != null && reportedVersion.isBlank()) {
            reportedVersion = null;
        }

        // Was the device offline before THIS beat? Judged from the previous heartbeat's age, read
        // BEFORE recordHeartbeat() overwrites it. The stored status cannot answer this: nothing
        // ever writes OFFLINE (the monitor is derive-only, and deriveStatus below always sees the
        // fresh beat), so a stored-status check never saw a recovery and DEVICE_OFFLINE incidents
        // stayed open forever. Two thresholds, on purpose:
        //  - shownOffline: the status every surface displayed (DeviceStatusEvaluator and
        //    device_status_view: 15 min + 60 s grace). Drives the transition event/broadcast, so
        //    a 15–16 min gap never announces an OFFLINE→ONLINE nobody ever saw.
        //  - offlineIncidentPossible: DeviceHealthMonitor opens DEVICE_OFFLINE at 15 min flat, so
        //    the resolve must fire from there.
        // A first-ever beat (null) counts as coming online on both.
        Instant beatAt = Instant.now();
        var previousHeartbeat = device.getLastHeartbeatAt();
        boolean shownOffline = DeviceStatusEvaluator.isOffline(previousHeartbeat, beatAt);
        boolean offlineIncidentPossible = previousHeartbeat == null
                || previousHeartbeat.isBefore(beatAt.minus(DeviceHealthMonitor.HEARTBEAT_THRESHOLD));
        List<String> resolveIncidentTypes = new ArrayList<>(2);

        device.recordHeartbeat();
        // AUTH-02: an authenticated beat proves the box still holds its token, so an open
        // re-registration window is unnecessary — and would only let someone else take the device.
        device.closeReregistrationWindow();
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
        recordRemoteCapability(device, remote);
        if (reportedVersion != null) {
            device.setCurrentContentVersion(reportedVersion);
        }
        if (sourceIp != null && !sourceIp.isBlank()) {
            // last_known_ip is VARCHAR(45) (IPv6 + zone). RemoteIpValve copies X-Forwarded-For from a
            // trusted proxy without validating it; an oversized value must not fail the beat.
            if (sourceIp.length() <= LAST_KNOWN_IP_MAX) {
                device.setLastKnownIp(sourceIp);
            } else {
                log.info("Device {} heartbeat source IP longer than {} chars — not stored", deviceId, LAST_KNOWN_IP_MAX);
            }
        }

        var previousStatus = shownOffline ? Device.Status.OFFLINE : device.getStatus();
        var newStatus = deriveStatus(device);

        if (previousStatus != newStatus) {
            device.setStatus(newStatus);
            emitStatusChangeEvent(device, previousStatus, newStatus);
        }
        // Recovery: the device is back after long enough for the monitor to have opened a
        // DEVICE_OFFLINE incident. Only RECORDED here — resolveRecoveredIncidents closes it after
        // this transaction commits (see there). The auto-resolver skips manually resolved
        // incidents, so over-reporting is harmless; a resolve that fails is picked up by
        // DeviceHealthMonitor's recovery sweep.
        if (offlineIncidentPossible && newStatus != Device.Status.OFFLINE) {
            resolveIncidentTypes.add(DeviceHealthMonitor.EVENT_OFFLINE);
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
            // → close any open CONTENT_VERSION_MISMATCH incident (after commit, as above).
            if (wasMismatched && !mismatch) {
                resolveIncidentTypes.add(DeviceHealthMonitor.EVENT_CONTENT_MISMATCH);
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
                expectedVersion, syncRequired, desiredVolume, syncGroupId,
                resolveDesiredRemoteSession(deviceId), resolveIncidentTypes);
    }

    /**
     * Close the incidents a beat recovered from ({@link HeartbeatResult#resolveIncidentTypes()}).
     * Deliberately NOT {@code @Transactional}, and must be called AFTER {@link #processHeartbeat}
     * has returned — i.e. once the beat has committed and released its pooled connection. Each
     * resolve then runs in a transaction of its own, one connection at a time. Resolving inside
     * the beat instead either joins its transaction (a failing resolve marks it rollback-only and
     * the beat, a liveness signal, fails at commit) or nests a second one (two connections per
     * recovery beat: after a regional outage every device recovers at once and the pool deadlocks).
     * Best-effort: a failure is logged and DeviceHealthMonitor's recovery sweep retries it.
     */
    public void resolveRecoveredIncidents(Long deviceId, HeartbeatResult result) {
        for (String eventType : result.resolveIncidentTypes()) {
            try {
                incidentService.autoResolveOnRecovery(deviceId, eventType);
            } catch (Exception e) {
                log.warn("Auto-resolve failed [device={}, type={}]: {}", deviceId, eventType, e.getMessage());
            }
        }
    }

    /**
     * Store the device's reported remote capability. Every failure mode here — an unknown enum
     * value, a nonsense resolution, even an unexpected runtime exception — is absorbed: the
     * heartbeat is a liveness signal first, and a capability reporter bug must not be able to
     * take a fleet offline. Same posture as {@code volume}.
     */
    private void recordRemoteCapability(Device device, RemoteCapabilityReport remote) {
        if (remote == null) {
            return;   // old client, or simply nothing to report — leave what we know untouched
        }
        try {
            device.recordRemoteCapability(
                    remote.supported(),
                    sanitizeToken(device.getId(), "remote.input", remote.input(), REMOTE_INPUT_VALUES),
                    sanitizeToken(device.getId(), "remote.transport", remote.transport(), REMOTE_TRANSPORT_VALUES),
                    sanitizeDimension(device.getId(), "remote.maxWidth", remote.maxWidth()),
                    sanitizeDimension(device.getId(), "remote.maxHeight", remote.maxHeight()),
                    Instant.now());
        } catch (Exception e) {
            log.warn("Remote capability report rejected [device={}]: {}", device.getId(), e.getMessage());
        }
    }

    /** Unknown token → {@code null} + WARN. Never throws. */
    private static String sanitizeToken(Long deviceId, String field, String value, List<String> allowed) {
        if (value == null) {
            return null;
        }
        var normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (allowed.contains(normalized)) {
            return normalized;
        }
        log.warn("Device {} reported unknown {} value '{}' — ignoring (allowed: {})",
                deviceId, field, value, allowed);
        return null;
    }

    /** Non-positive or absurd dimension → {@code null} + WARN. Never throws. */
    private static Integer sanitizeDimension(Long deviceId, String field, Integer value) {
        if (value == null) {
            return null;
        }
        if (value <= 0 || value > 7680) {
            log.warn("Device {} reported out-of-range {} value {} — ignoring", deviceId, field, value);
            return null;
        }
        return value;
    }

    /**
     * Best-effort desired-state lookup. A remote-session read failure must never poison the beat
     * — the device simply learns about the session one cycle later, exactly like the sync-group
     * resolution above.
     */
    private RemoteSessionService.DesiredRemoteSession resolveDesiredRemoteSession(Long deviceId) {
        try {
            return remoteSessionService.desiredFor(deviceId).orElse(null);
        } catch (Exception e) {
            log.warn("Desired remote session resolution failed [device={}]: {}", deviceId, e.getMessage());
            return null;
        }
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

    private static Event.Priority priorityForTransition(Device.Status from, Device.Status to) {
        if (to == Device.Status.OFFLINE) return Event.Priority.HIGH;
        if (from == Device.Status.OFFLINE && to == Device.Status.ONLINE) return Event.Priority.MEDIUM;
        return Event.Priority.INFO;
    }

    /**
     * The device's reported remote view/control capability, lifted off the heartbeat body.
     * Every field is optional — records deserialize missing JSON fields as {@code null}, which
     * is exactly what an old client that knows nothing about this block sends.
     */
    public record RemoteCapabilityReport(Boolean supported, String input, String transport,
                                          Integer maxWidth, Integer maxHeight) {}

    public record HeartbeatResult(
            Long deviceId,
            Device.Status status,
            List<RemoteAction> pendingActions,
            String expectedContentVersion,
            boolean syncRequired,
            int desiredVolume,
            // Synchronized-playback group (§1.1): facility ?? group ?? region, or null ⇒ free-run solo.
            String syncGroupId,
            // Remote view/control desired state. null ⇒ no session wanted; the device stops any
            // running one. Nullable on the wire so old clients are unaffected.
            RemoteSessionService.DesiredRemoteSession desiredRemoteSession,
            // Server-internal, never on the wire: incident types this beat recovered from, for
            // resolveRecoveredIncidents to close after the beat has committed. Never null.
            List<String> resolveIncidentTypes
    ) {
        public HeartbeatResult {
            resolveIncidentTypes = resolveIncidentTypes == null ? List.of() : List.copyOf(resolveIncidentTypes);
        }

        // Backwards-compatible 3-arg constructor for callers that don't track versions/volume.
        public HeartbeatResult(Long deviceId, Device.Status status, List<RemoteAction> pendingActions) {
            this(deviceId, status, pendingActions, null, false, DeviceVolumeResolver.DEFAULT_VOLUME, null, null);
        }

        // Back-compat 5-arg (pre-volume) constructor — defaults desiredVolume to the resolver default.
        public HeartbeatResult(Long deviceId, Device.Status status, List<RemoteAction> pendingActions,
                               String expectedContentVersion, boolean syncRequired) {
            this(deviceId, status, pendingActions, expectedContentVersion, syncRequired,
                    DeviceVolumeResolver.DEFAULT_VOLUME, null, null);
        }

        // Back-compat 7-arg (pre-remote-control) constructor — no desired remote session.
        public HeartbeatResult(Long deviceId, Device.Status status, List<RemoteAction> pendingActions,
                               String expectedContentVersion, boolean syncRequired, int desiredVolume,
                               String syncGroupId) {
            this(deviceId, status, pendingActions, expectedContentVersion, syncRequired,
                    desiredVolume, syncGroupId, null);
        }

        // Back-compat 8-arg constructor — nothing to resolve.
        public HeartbeatResult(Long deviceId, Device.Status status, List<RemoteAction> pendingActions,
                               String expectedContentVersion, boolean syncRequired, int desiredVolume,
                               String syncGroupId, RemoteSessionService.DesiredRemoteSession desiredRemoteSession) {
            this(deviceId, status, pendingActions, expectedContentVersion, syncRequired,
                    desiredVolume, syncGroupId, desiredRemoteSession, List.of());
        }
    }
}
