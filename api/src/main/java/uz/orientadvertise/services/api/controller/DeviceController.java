package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.api.dto.SetVolumeRequest;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.DeviceActionType;
import uz.orientadvertise.services.domain.model.DeviceStatusView;
import uz.orientadvertise.services.domain.model.RemoteAction;
import uz.orientadvertise.services.service.DeviceActionService;
import uz.orientadvertise.services.service.DeviceDiagnosticsService;
import uz.orientadvertise.services.service.DeviceDiagnosticsService.DiagnosticsView;
import uz.orientadvertise.services.service.DeviceHeartbeatService;
import uz.orientadvertise.services.service.DeviceManagementService;
import uz.orientadvertise.services.service.DeviceRegistrationRateLimiter;
import uz.orientadvertise.services.service.DeviceRegistrationService;
import uz.orientadvertise.services.service.RemoteActionService;
import uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmResult;
import uz.orientadvertise.services.service.RemoteActionService.DeviceConfirmStatus;
import uz.orientadvertise.services.service.DeviceSyncService;
import uz.orientadvertise.services.service.PlaylistControlService;
import uz.orientadvertise.services.service.PlaylistControlService.ControlAction;
import uz.orientadvertise.services.service.DeviceSyncService.ConfirmResult;
import uz.orientadvertise.services.service.DeviceSyncService.PlaylistEntry;
import uz.orientadvertise.services.service.DeviceSyncService.PlaylistView;
import uz.orientadvertise.services.service.DeviceSyncService.PlaylistViewItem;
import uz.orientadvertise.services.service.DeviceSyncService.SyncFileToAdd;
import uz.orientadvertise.services.service.DeviceSyncService.SyncPlan;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceRegistrationService registrationService;
    private final DeviceHeartbeatService heartbeatService;
    private final DeviceManagementService managementService;
    private final DeviceSyncService syncService;
    private final PlaylistControlService playlistControlService;
    private final DeviceActionService deviceActionService;
    private final RemoteActionService remoteActionService;
    private final DeviceDiagnosticsService diagnosticsService;
    private final DeviceRegistrationRateLimiter registrationRateLimiter;

    public DeviceController(DeviceRegistrationService registrationService,
                             DeviceHeartbeatService heartbeatService,
                             DeviceManagementService managementService,
                             DeviceSyncService syncService,
                             PlaylistControlService playlistControlService,
                             DeviceActionService deviceActionService,
                             RemoteActionService remoteActionService,
                             DeviceDiagnosticsService diagnosticsService,
                             DeviceRegistrationRateLimiter registrationRateLimiter) {
        this.registrationService = registrationService;
        this.heartbeatService = heartbeatService;
        this.managementService = managementService;
        this.syncService = syncService;
        this.playlistControlService = playlistControlService;
        this.deviceActionService = deviceActionService;
        this.remoteActionService = remoteActionService;
        this.diagnosticsService = diagnosticsService;
        this.registrationRateLimiter = registrationRateLimiter;
    }

    @PostMapping("/register")
    public ResponseEntity<DeviceRegistrationResponse> register(@Valid @RequestBody DeviceRegistrationRequest request,
                                                               jakarta.servlet.http.HttpServletRequest httpRequest) {
        // First contact has no token, so this endpoint is permitAll — rate-limit per source
        // IP to blunt serial-guessing / mass-registration.
        registrationRateLimiter.check(resolveSourceIp(httpRequest));
        var result = registrationService.register(request.serialNumber(), request.deviceName());
        var status = result.newRegistration() ? 201 : 200;
        return ResponseEntity.status(status).body(new DeviceRegistrationResponse(
                result.deviceId(),
                result.deviceToken(),
                result.serialNumber(),
                result.newRegistration() ? "registered" : "re-registered",
                result.syncGroupId()
        ));
    }

    @PostMapping("/{id}/heartbeat")
    @PreAuthorize("hasRole('DEVICE') and #id == authentication.principal")
    public ResponseEntity<HeartbeatResponse> heartbeat(
            @PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestBody(required = false) HeartbeatRequest request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String reportedVersion = request == null ? null : request.contentVersion();
        Integer reportedVolume = request == null ? null : request.volume();
        String sourceIp = resolveSourceIp(httpRequest);
        var result = heartbeatService.processHeartbeat(id, reportedVersion, sourceIp, reportedVolume);
        var pending = result.pendingActions().stream()
                .map(PendingActionDto::from)
                .toList();
        return ResponseEntity.ok(new HeartbeatResponse(
                result.deviceId(),
                result.status().name(),
                Instant.now(),
                pending,
                result.expectedContentVersion(),
                result.syncRequired(),
                result.desiredVolume(),
                result.syncGroupId()
        ));
    }

    /**
     * Heartbeat body. {@code volume} (optional, 0-100) is the device's current output volume —
     * the server stores it (clamped) and never fails the beat on a bad value. The response's
     * {@code desiredVolume} is the target the device should converge to.
     */
    public record HeartbeatRequest(String contentVersion, Integer volume) {}

    /**
     * Pull the client IP from {@code X-Forwarded-For} (first hop) when present, falling
     * back to {@code request.getRemoteAddr()}. The header is typically set by the
     * reverse proxy in front of the API; trust the first entry which is the original
     * client.
     */
    private static String resolveSourceIp(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) return null;
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Returns the device's currently-assigned playlist as an ordered list with file URLs
     * and durations. Edge case: no assigned playlist → 200 with empty {@code items} array
     * (NOT 404 — only an unknown device returns 404). Edge case: mid-playback playlist
     * update — the response always reflects the latest server state and includes
     * {@code contentVersion}; the device decides when to switch (typically at the next
     * item boundary) so the currently-playing item is not interrupted.
     */
    /**
     * Operator-issued single-device action. Creates a {@code RemoteAction} that the
     * device picks up via WebSocket push or heartbeat poll.
     *
     * <p>Edge cases:
     * <ul>
     *   <li>Max 1 PENDING per (device, action type) — duplicate returns 409.</li>
     *   <li>Total pending per device capped at 10 — request beyond that returns 409.</li>
     *   <li>VOLUME_SET requires {@code volume} in [0, 100] — missing/out-of-range returns 400.</li>
     * </ul>
     */
    /**
     * Aggregate diagnostic snapshot for the operator console. Cached for 30 seconds in
     * Redis under cache name {@code diagnostics} keyed by deviceId.
     *
     * <p>Edge case: a device that has never heartbeated returns the envelope with
     * {@code lastHeartbeatAt: null}, {@code lastKnownIp: null}, empty event/action lists,
     * and {@code pendingActionCount: 0} — not a 404. Only an unknown device id is 404.
     */
    @GetMapping("/{id}/diagnostics")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<DiagnosticsView> diagnostics(@PathVariable Long id) {
        managementService.assertScopeForDevice(id);   // operator scope ⇒ 404 if out of scope
        return ResponseEntity.ok(diagnosticsService.getDiagnostics(id));
    }

    /**
     * Device polls for outstanding actions to execute. Returns only PENDING entries — once
     * the device confirms (or the action expires), it falls off this list.
     *
     * <p>Intended for devices that don't keep an open WebSocket; devices with an open
     * {@code /ws/devices/{id}} socket get the same notifications pushed live.
     */
    @GetMapping("/{id}/actions/pending")
    @PreAuthorize("hasRole('DEVICE') and #id == authentication.principal")
    public ResponseEntity<List<PendingActionDto>> getPendingActions(@PathVariable Long id) {
        var pending = remoteActionService.getPendingByDevice(id);
        var dtos = pending.stream().map(PendingActionDto::from).toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Operator-facing remote-action history. Complements
     * {@link #getPendingActions(Long)}: that endpoint is the device's pickup queue
     * (PENDING only, {@code permitAll}, device-token authenticated); this one is the
     * full audit trail for human operators (every status, role-gated, paginated).
     *
     * <p>Validation, range capping, and the forced {@code issuedAt DESC} sort live in
     * {@link RemoteActionService#getHistory}. An unknown deviceId surfaces as 404 even
     * for an authorized caller — the device existence check fires before any other
     * validation so probing 401/403/400 cannot enumerate live device ids.
     */
    @GetMapping("/{id}/actions")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<Page<RemoteActionDto>> getActionHistory(
            @PathVariable Long id,
            @RequestParam(required = false) RemoteAction.Status status,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Pageable pageable) {
        managementService.assertScopeForDevice(id);   // operator scope ⇒ 404 if out of scope
        Page<RemoteAction> page = remoteActionService.getHistory(
                id, status, actionType, from, to, pageable);
        return ResponseEntity.ok(page.map(RemoteActionDto::from));
    }

    /**
     * Device-side confirmation of an action it picked up.
     *
     * <p>Edge cases:
     * <ul>
     *   <li>Unknown action id → 200 with {@code outcome: UNKNOWN} (don't crash a confused
     *       or restarted device with a 404).</li>
     *   <li>Confirmation arrives after the deadline → recorded as {@code CONFIRMED_LATE}
     *       so the operator console can distinguish on-time vs late delivery.</li>
     *   <li>FAILED takes precedence over timing — late FAILED is still FAILED.</li>
     * </ul>
     */
    @PostMapping("/{id}/actions/{actionId}/confirm")
    @PreAuthorize("hasRole('DEVICE') and #id == authentication.principal")
    public ResponseEntity<ActionConfirmResponse> confirmAction(
            @PathVariable Long id,
            @PathVariable Long actionId,
            @Valid @RequestBody ActionConfirmRequest request) {
        DeviceConfirmResult result = remoteActionService.processDeviceConfirmation(
                id, actionId, request.status(), request.result());
        return ResponseEntity.ok(ActionConfirmResponse.from(result));
    }

    /**
     * @PreAuthorize restricts to ADMIN and OPERATOR — VIEWER and ADVERTISER get 403.
     * The rule applies uniformly to all action types including VOLUME_SET; volume is a
     * privileged operation because it changes what end-customers experience at the venue.
     */
    @PostMapping("/{id}/actions")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<DeviceActionResponse> issueAction(@PathVariable Long id,
                                                             @Valid @RequestBody DeviceActionRequest request) {
        managementService.assertScopeForDevice(id);   // operator scope ⇒ 404 if out of scope
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var issuedBy = auth != null ? auth.getName() : "system";

        var action = deviceActionService.issueAction(id, request.type(), request.volume(), issuedBy);
        return ResponseEntity.status(202).body(DeviceActionResponse.from(action));
    }

    /**
     * Operator-issued playlist transport command. Creates a {@link uz.orientadvertise.services.domain.model.RemoteAction}
     * with a 10-minute expiry. Edge case: JUMP position is range-checked against the
     * resolved playlist BEFORE queuing — out-of-range yields 400, no remote action created.
     */
    @PostMapping("/{id}/playlist/control")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<PlaylistControlResponse> playlistControl(@PathVariable Long id,
                                                                    @Valid @RequestBody PlaylistControlRequest request) {
        managementService.assertScopeForDevice(id);   // operator scope ⇒ 404 if out of scope
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var issuedBy = auth != null ? auth.getName() : "system";

        var action = playlistControlService.issueControl(id, request.action(), request.position(), issuedBy);
        return ResponseEntity.status(202).body(PlaylistControlResponse.from(action));
    }

    /**
     * Resolved playlist for a device — used by <b>both</b> the TV-Box (the original audience)
     * and the operator UI's device-detail panel.
     *
     * <p><b>Auth:</b> {@code permitAll} in {@link uz.orientadvertise.services.api.security.SecurityConfig}.
     * The TV-Box hits this without a JWT; the operator UI hits it with the operator's JWT,
     * which is also accepted (Spring Security treats the JWT as superfluous on a permitAll
     * matcher). The response is identical for both — no role-aware projection — because the
     * operator panel renders exactly what the device sees, including resolved sources and
     * presigned URLs. If a future change needs to redact device-only fields for operators,
     * split this into {@code GET /api/devices/{id}/playlist} (device) and
     * {@code GET /api/devices/{id}/playlist/operator-view} (role-gated) — do not narrow this
     * matcher in place, because TV-Boxes have no token to present.
     */
    @GetMapping("/{id}/playlist")
    @PreAuthorize("hasRole('DEVICE') and #id == authentication.principal")
    public ResponseEntity<PlaylistResponse> playlist(@PathVariable Long id) {
        PlaylistView view = syncService.getPlaylistView(id);
        return ResponseEntity.ok(PlaylistResponse.from(view));
    }

    /**
     * Returns the diff a device must apply to reach the server's current expected content state.
     * Edge case: {@code currentVersion} omitted/null = full content list (fresh device).
     * Presigned URLs default to a 60-minute TTL; each call regenerates them, so re-syncing
     * after a partial download yields fresh URLs without any server-side state.
     */
    @GetMapping("/{id}/sync")
    @PreAuthorize("hasRole('DEVICE') and #id == authentication.principal")
    public ResponseEntity<SyncResponse> sync(
            @PathVariable Long id,
            @RequestParam(required = false) String currentVersion,
            @RequestParam(required = false) Set<Long> currentFileIds) {
        Set<Long> held = currentFileIds == null ? Collections.emptySet() : new LinkedHashSet<>(currentFileIds);
        SyncPlan plan = syncService.computeSyncPlan(id, currentVersion, held);
        return ResponseEntity.ok(SyncResponse.from(plan));
    }

    /**
     * Server clock for the device's offset estimation (synchronized-playback time layer).
     *
     * <p>Deliberately cheap: it neither loads the device nor writes {@code last_heartbeat_at} nor
     * recomputes status — a device pings it ~5× in quick succession to pick the min-RTT sample, so
     * it must stay allocation-light. Do <b>not</b> route this through {@code DeviceHeartbeatService}.
     */
    @GetMapping("/{id}/time")
    @PreAuthorize("hasRole('DEVICE') and #id == authentication.principal")
    public ResponseEntity<ServerTimeResponse> time(@PathVariable Long id) {
        return ResponseEntity.ok(new ServerTimeResponse(System.currentTimeMillis()));
    }

    /**
     * Device confirms it has finished applying a sync plan.
     *
     * <p>Edge case: unexpected version confirmed → response carries
     * {@code status=MISMATCH, syncRequired=true}. Pending state is preserved so the 30-min
     * timeout monitor still runs; the device should call {@code /sync} again to recover.
     */
    @PostMapping("/{id}/sync/confirm")
    @PreAuthorize("hasRole('DEVICE') and #id == authentication.principal")
    public ResponseEntity<SyncConfirmResponse> confirmSync(
            @PathVariable Long id,
            @Valid @RequestBody SyncConfirmRequest request) {
        ConfirmResult result = syncService.confirmSync(id, request.reportedVersion());
        return ResponseEntity.ok(SyncConfirmResponse.from(result));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<Page<DeviceListItem>> list(
            @RequestParam(required = false) Device.Status status,
            @RequestParam(required = false) Long regionId,
            // projectId: project-scoped device picker (decision 3). The view exposes only
            // regionId, so the repository narrows via a Region subselect.
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long facilityId,
            @RequestParam(required = false) Long deviceGroupId,
            // unassigned=true → only devices with device_group_id IS NULL ("needs
            // grouping" bucket). Unset or =false: no constraint. Combining
            // unassigned=true with a non-null deviceGroupId is mutually exclusive and
            // returns 400 (enforced in DeviceManagementService.list).
            @RequestParam(required = false) Boolean unassigned,
            @RequestParam(required = false) String serial,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String facilityName,
            // hasActivePlaylist (tri-state): null → no constraint; true → only devices with a
            // resolved active playlist now (activePlaylistId != null); false → the "needs content"
            // bucket (no active playlist). DISTINCT from unassigned (device_group_id IS NULL):
            // the two axes are orthogonal and may be combined freely (no mutual-exclusion guard) —
            // a group-less device can still get a playlist via a REGION-targeted assignment.
            // A non-boolean value (e.g. ?hasActivePlaylist=notabool) is a 400 (type mismatch).
            @RequestParam(required = false) Boolean hasActivePlaylist,
            // syncUnassigned=true → only devices with sync_group_id IS NULL (the "not yet in a
            // sync group / sales point" bucket, used by the FE sync-group member picker). A
            // DISTINCT axis from `unassigned` (device_group_id IS NULL) — do NOT overload that
            // param. Unset/false: no constraint. Orthogonal to every other filter (no guard).
            @RequestParam(required = false) Boolean syncUnassigned,
            Pageable pageable) {
        // Empty page (zero results) is returned naturally by Spring Data — never 404.
        var page = managementService.list(status, regionId, projectId, facilityId, deviceGroupId,
                unassigned, serial, name, facilityName, hasActivePlaylist, syncUnassigned, pageable);
        return ResponseEntity.ok(page.map(DeviceListItem::from));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<DeviceDetail> getById(@PathVariable Long id) {
        var device = managementService.getById(id);
        return ResponseEntity.ok(DeviceDetail.from(device, managementService.computedStatus(id),
                managementService.effectiveVolume(id), managementService.syncGroupName(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<DeviceDetail> update(@PathVariable Long id,
                                                @Valid @RequestBody UpdateDeviceRequest request) {
        var device = managementService.update(id, request.name());
        return ResponseEntity.ok(DeviceDetail.from(device, managementService.computedStatus(id),
                managementService.effectiveVolume(id), managementService.syncGroupName(id)));
    }

    /**
     * Move a device into a new region (and optionally a facility within that region).
     * {@code facilityId} is nullable — pass {@code null} to clear the facility while
     * keeping the device under the region directly.
     *
     * <p>Edge cases:
     * <ul>
     *   <li>{@code facilityId} set but the facility belongs to a different region → 400.</li>
     *   <li>Device currently belongs to a {@code DeviceGroup} from a different project →
     *       409. Operator must clear the membership first via
     *       {@code DELETE /api/device-groups/{gid}/devices/{id}} — relocating to a region in
     *       a different project would otherwise break the group's project invariant. A
     *       same-project cross-region move is allowed.</li>
     * </ul>
     */
    @PutMapping("/{id}/location")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<DeviceDetail> setLocation(@PathVariable Long id,
                                                     @Valid @RequestBody SetLocationRequest request) {
        var device = managementService.setLocation(id, request.regionId(), request.facilityId());
        return ResponseEntity.ok(DeviceDetail.from(device, managementService.computedStatus(id),
                managementService.effectiveVolume(id), managementService.syncGroupName(id)));
    }

    /**
     * Set a device's persistent volume override (0-100). Applied to the device on its next
     * heartbeat (desired-state reconciliation). Out-of-scope ⇒ 404; out-of-range ⇒ 400.
     */
    @PutMapping("/{id}/volume")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<Void> setVolume(@PathVariable Long id,
                                          @Valid @RequestBody SetVolumeRequest request) {
        managementService.setVolume(id, request.volume());
        return ResponseEntity.noContent().build();
    }

    /** Clear a device's volume override so it inherits its group's volume (or the default, 100). */
    @DeleteMapping("/{id}/volume")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<Void> clearVolume(@PathVariable Long id) {
        managementService.clearVolume(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Apply a volume override to every device in the caller's operator scope — an ADMIN with no
     * scope restriction hits every device; an operator only their assigned projects. Returns
     * {@code {affected: <int>}}. Mapped at {@code /api/devices/volume}; the literal {@code volume}
     * segment takes precedence over the {@code /{id}} update mapping.
     */
    @PutMapping("/volume")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<ApplyVolumeResponse> setVolumeForAll(@Valid @RequestBody SetVolumeRequest request) {
        int affected = managementService.setVolumeForAll(request.volume());
        return ResponseEntity.ok(new ApplyVolumeResponse(affected));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        managementService.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    public record DeviceRegistrationRequest(
            @NotBlank(message = "Serial number is required") String serialNumber,
            String deviceName
    ) {}

    public record DeviceRegistrationResponse(Long deviceId, String deviceToken, String serialNumber, String status,
                                             // Sync group (§1.1): usually the region-level fallback at
                                             // registration; the meaningful group is delivered by heartbeat
                                             // once an operator places the device. Nullable.
                                             String syncGroupId) {}

    /** Server UTC as Unix epoch milliseconds — the device folds this into its clock offset (§1.2). */
    public record ServerTimeResponse(long serverUnixMs) {}

    public record HeartbeatResponse(
            Long deviceId,
            String status,
            Instant serverTime,
            List<PendingActionDto> pendingActions,
            String expectedContentVersion,
            boolean syncRequired,
            // Resolved effective target volume (device override ?? group volume ?? 100). Always
            // present — the device should set its output to this and report back on the next beat.
            Integer desiredVolume,
            // Synchronized-playback group (§1.1): facility ?? group ?? region. Echoed every beat so a
            // relocated device re-groups promptly; null ⇒ the device free-runs solo. Nullable.
            String syncGroupId
    ) {}

    public record PendingActionDto(Long actionId, String actionType, String payload, Instant issuedAt, Instant expiresAt) {
        public static PendingActionDto from(RemoteAction action) {
            return new PendingActionDto(
                    action.getId(),
                    action.getActionType(),
                    action.getPayload(),
                    action.getIssuedAt(),
                    action.getExpiresAt()
            );
        }
    }

    public record UpdateDeviceRequest(@NotBlank String name) {}

    public record SetLocationRequest(
            @NotNull(message = "regionId is required") Long regionId,
            Long facilityId
    ) {}

    /** Result of {@code PUT /api/devices/volume} — how many in-scope devices got the override. */
    public record ApplyVolumeResponse(int affected) {}

    public record DeviceListItem(Long id, String serialNumber, String name, String computedStatus,
                                  Long regionId, Long facilityId, String facilityName,
                                  Long deviceGroupId,
                                  // The numeric sync_group entity id (NEVER the prefixed "sg-" wire
                                  // string) sourced from the view. The FE picker's numOrNull parser
                                  // throws on a string and then drops every device row.
                                  Long syncGroupId, Instant lastHeartbeatAt,
                                  Long activePlaylistId, String activePlaylistName) {
        public static DeviceListItem from(DeviceStatusView v) {
            return new DeviceListItem(v.getId(), v.getSerialNumber(), v.getName(),
                    v.getComputedStatus().name(), v.getRegionId(),
                    v.getFacilityId(), v.getFacilityName(),
                    v.getDeviceGroupId(), v.getSyncGroupId(), v.getLastHeartbeatAt(),
                    v.getActivePlaylistId(), v.getActivePlaylistName());
        }
    }

    public record ActionConfirmRequest(
            @jakarta.validation.constraints.NotNull(message = "status is required") DeviceConfirmStatus status,
            String result
    ) {}

    /**
     * Operator-facing remote-action projection — full audit-trail surface, including
     * every status (PENDING, CONFIRMED, CONFIRMED_LATE, EXPIRED, FAILED) and the
     * device's reported {@code result} payload. Distinct from {@link PendingActionDto},
     * which is the slim device-side projection that omits {@code status}/{@code result}/
     * {@code confirmedAt}/{@code issuedBy} because the device knows none of those when
     * polling.
     */
    public record RemoteActionDto(Long actionId, String actionType, String status,
                                    String payload, Instant issuedAt, Instant expiresAt,
                                    Instant confirmedAt, String issuedBy, String result) {
        public static RemoteActionDto from(RemoteAction a) {
            return new RemoteActionDto(
                    a.getId(),
                    a.getActionType(),
                    a.getStatus() != null ? a.getStatus().name() : null,
                    a.getPayload(),
                    a.getIssuedAt(),
                    a.getExpiresAt(),
                    a.getConfirmedAt(),
                    a.getIssuedBy(),
                    a.getResult());
        }
    }

    public record ActionConfirmResponse(Long actionId, String outcome, String finalStatus) {
        public static ActionConfirmResponse from(DeviceConfirmResult r) {
            return new ActionConfirmResponse(r.actionId(), r.outcome().name(),
                    r.finalStatus() == null ? null : r.finalStatus().name());
        }
    }

    public record DeviceActionRequest(
            @jakarta.validation.constraints.NotNull(message = "type is required") DeviceActionType type,
            // Bean Validation enforces the 0-100 range at request binding so a malformed
            // volume short-circuits with a fieldErrors payload before reaching the service.
            // Service-layer DeviceActionService.buildPayload re-checks the range as
            // defense-in-depth (e.g. internal callers that bypass @Valid). Null is allowed
            // here because volume is only required for VOLUME_SET — that conditional rule
            // is enforced in the service.
            @jakarta.validation.constraints.Min(value = 0, message = "volume must be in [0, 100]")
            @jakarta.validation.constraints.Max(value = 100, message = "volume must be in [0, 100]")
            Integer volume
    ) {}

    public record DeviceActionResponse(Long actionId, Long deviceId, String actionType,
                                        String status, String payload, Instant issuedAt,
                                        Instant expiresAt, String issuedBy) {
        public static DeviceActionResponse from(RemoteAction a) {
            return new DeviceActionResponse(a.getId(), a.getDevice().getId(), a.getActionType(),
                    a.getStatus().name(), a.getPayload(), a.getIssuedAt(),
                    a.getExpiresAt(), a.getIssuedBy());
        }
    }

    public record PlaylistControlRequest(
            @jakarta.validation.constraints.NotNull(message = "action is required") ControlAction action,
            Integer position
    ) {}

    public record PlaylistControlResponse(Long actionId, Long deviceId, String actionType,
                                           String status, String payload, Instant issuedAt,
                                           Instant expiresAt, String issuedBy) {
        public static PlaylistControlResponse from(uz.orientadvertise.services.domain.model.RemoteAction a) {
            return new PlaylistControlResponse(a.getId(), a.getDevice().getId(), a.getActionType(),
                    a.getStatus().name(), a.getPayload(), a.getIssuedAt(),
                    a.getExpiresAt(), a.getIssuedBy());
        }
    }

    public record PlaylistResponse(Long deviceId, Long playlistId, String playlistName,
                                    String contentVersion, int totalDurationSeconds,
                                    List<PlaylistItemDto> items) {
        public static PlaylistResponse from(PlaylistView v) {
            return new PlaylistResponse(v.deviceId(), v.playlistId(), v.playlistName(),
                    v.contentVersion(), v.totalDurationSeconds(),
                    v.items().stream().map(PlaylistItemDto::from).toList());
        }
    }

    public record PlaylistItemDto(int index, int position, Long fileId, String name, String contentType,
                                   String presignedUrl, Integer durationSeconds,
                                   String checksum, long sizeBytes) {
        static PlaylistItemDto from(PlaylistViewItem i) {
            return new PlaylistItemDto(i.index(), i.position(), i.fileId(), i.name(), i.contentType(),
                    i.presignedUrl(), i.durationSeconds(), i.checksum(), i.sizeBytes());
        }
    }

    public record SyncConfirmRequest(@NotBlank(message = "reportedVersion is required") String reportedVersion) {}

    public record SyncConfirmResponse(Long deviceId, String status, String expectedVersion,
                                       String reportedVersion, boolean syncRequired) {
        public static SyncConfirmResponse from(ConfirmResult r) {
            return new SyncConfirmResponse(r.deviceId(), r.status().name(),
                    r.expectedVersion(), r.reportedVersion(), r.syncRequired());
        }
    }

    public record SyncResponse(
            Long deviceId,
            String expectedContentVersion,
            boolean fullSync,
            List<SyncFileToAddDto> filesToAdd,
            List<Long> filesToDelete,
            List<PlaylistEntryDto> playlistOrder,
            int presignedUrlExpiryMinutes,
            Instant presignedUrlsExpireAt,
            // --- synchronized-playback time layer (§1.3). Epoch-MILLISECOND longs (NOT ISO-8601),
            // absent (null) for a solo/ungrouped device or when the loop is not yet anchored. The
            // device computes positionInLoop() from these + its clock offset; no device-to-device
            // traffic. Audio/volume stays out-of-band via desiredVolume + VOLUME_SET (unchanged). ---
            String syncGroupId,
            Long anchorEpochMs,
            long loopDurationMs,
            Long activateAt) {

        public static SyncResponse from(SyncPlan p) {
            return new SyncResponse(
                    p.deviceId(),
                    p.expectedContentVersion(),
                    p.fullSync(),
                    p.filesToAdd().stream().map(SyncFileToAddDto::from).toList(),
                    p.filesToDelete(),
                    p.playlistOrder().stream().map(PlaylistEntryDto::from).toList(),
                    p.presignedUrlExpiryMinutes(),
                    p.presignedUrlsExpireAt(),
                    p.syncGroupId(),
                    p.anchorEpochMs(),
                    p.loopDurationMs(),
                    p.activateAt());
        }
    }

    public record SyncFileToAddDto(Long fileId, String name, String contentType, long sizeBytes,
                                    Integer durationSeconds, String checksum, String presignedUrl) {
        static SyncFileToAddDto from(SyncFileToAdd f) {
            return new SyncFileToAddDto(f.fileId(), f.name(), f.contentType(), f.sizeBytes(),
                    f.durationSeconds(), f.checksum(), f.presignedUrl());
        }
    }

    public record PlaylistEntryDto(int index, int position, Long fileId, Integer durationSeconds,
                                    // Synchronized-playback slot timeline (§1.3): loop-relative start
                                    // offset and length in ms. slotDurationMs is always positive.
                                    long slotStartMs, long slotDurationMs) {
        static PlaylistEntryDto from(PlaylistEntry e) {
            return new PlaylistEntryDto(e.index(), e.position(), e.fileId(), e.durationSeconds(),
                    e.slotStartMs(), e.slotDurationMs());
        }
    }

    public record DeviceDetail(Long id, String serialNumber, String name, String computedStatus,
                                Long regionId, Long facilityId, Long deviceGroupId,
                                // syncGroupId: the numeric sync-group entity id (proxy-safe via
                                // getSyncGroup().getId()); syncGroupName: resolved inside the service
                                // tx (getSyncGroup().getName() is lazy — reading it here, after the tx
                                // closes under open-in-view:false, would 500). Both null ⇒ ungrouped.
                                Long syncGroupId, String syncGroupName,
                                Instant lastHeartbeatAt, Instant registeredAt,
                                Instant createdAt, Instant updatedAt, Instant deletedAt,
                                boolean deleted,
                                Integer reportedVolume, Integer volumeOverride, int effectiveVolume) {
        public static DeviceDetail from(Device d, Device.Status computedStatus, int effectiveVolume,
                                        String syncGroupName) {
            return new DeviceDetail(d.getId(), d.getSerialNumber(), d.getName(),
                    computedStatus != null ? computedStatus.name() : null,
                    d.getRegion() != null ? d.getRegion().getId() : null,
                    d.getFacility() != null ? d.getFacility().getId() : null,
                    d.getDeviceGroup() != null ? d.getDeviceGroup().getId() : null,
                    d.getSyncGroup() != null ? d.getSyncGroup().getId() : null, syncGroupName,
                    d.getLastHeartbeatAt(), d.getRegisteredAt(),
                    d.getCreatedAt(), d.getUpdatedAt(), d.getDeletedAt(),
                    d.isDeleted(),
                    d.getReportedVolume(), d.getDesiredVolume(), effectiveVolume);
        }
    }
}
