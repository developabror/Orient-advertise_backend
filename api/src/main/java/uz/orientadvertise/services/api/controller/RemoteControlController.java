package uz.orientadvertise.services.api.controller;

import java.time.Instant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.api.openapi.SensitiveEndpoint;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.service.DeviceManagementService;
import uz.orientadvertise.services.service.RemoteSessionService;
import uz.orientadvertise.services.service.RemoteSessionService.AckStatus;
import uz.orientadvertise.services.service.RemoteSessionService.RemoteCapabilityView;
import uz.orientadvertise.services.service.RemoteSessionService.RemoteSessionView;

/**
 * Remote view/control session lifecycle — {@code /api/devices/{id}/remote}.
 *
 * <p><b>This endpoint family carries no media.</b> It mints a rendezvous (an opaque session key
 * plus a signed, role-scoped, single-use ticket) and tells the caller which relay to dial. Video
 * and input flow browser ⇄ relay ⇄ device and never touch this service — see
 * {@code REMOTE_CONTROL_CONTRACT.md} §1.
 *
 * <p>Auth is split deliberately: start/stop/read are operator surfaces (JWT,
 * {@code ADMIN}/{@code OPERATOR} — {@code VIEWER} and {@code ADVERTISER} get 403), while
 * {@code /ack} is the <b>device</b> surface and is reached with {@code X-Device-Token} through
 * {@code DeviceTokenAuthFilter}. {@code SecurityConfig} pins both at the filter chain as well, so
 * an operator JWT cannot ack and a device token cannot start.
 *
 * <p>Operator project scope (V35) is enforced through
 * {@link DeviceManagementService#assertScopeForDevice(Long)} on every JWT path; out-of-scope
 * collapses to 404 like every other device sub-resource.
 */
@Tag(name = "Remote Control", description = "Remote view/control session lifecycle (control plane only — no media)")
@RestController
@RequestMapping("/api/devices/{id}/remote")
public class RemoteControlController {

    private final RemoteSessionService remoteSessionService;
    private final DeviceManagementService deviceManagementService;

    public RemoteControlController(RemoteSessionService remoteSessionService,
                                    DeviceManagementService deviceManagementService) {
        this.remoteSessionService = remoteSessionService;
        this.deviceManagementService = deviceManagementService;
    }

    /**
     * Start a session. Returns <b>201</b> with the viewer's relay URL and ticket.
     *
     * <p>Edge case: an offline device is <b>not</b> an error — the response is still 201 with
     * {@code deliveredVia: "HEARTBEAT"}, and the frontend shows "waiting for device".
     */
    @Operation(summary = "[SENSITIVE] Start a remote view/control session")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Session created"),
            @ApiResponse(responseCode = "403", description = "Caller is VIEWER/ADVERTISER"),
            @ApiResponse(responseCode = "404", description = "Device not found, soft-deleted, or out of scope"),
            @ApiResponse(responseCode = "409", description = "A PENDING/ACTIVE session already exists"),
            @ApiResponse(responseCode = "422", description = "Device reported capability.supported == false"),
            @ApiResponse(responseCode = "503", description = "Remote control is disabled on this server")
    })
    @SensitiveEndpoint
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<RemoteSessionResponse> start(
            @PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestBody(required = false) StartRemoteSessionRequest request) {
        deviceManagementService.assertScopeForDevice(id);   // operator scope ⇒ 404 if out of scope
        boolean viewOnly = request != null && Boolean.TRUE.equals(request.viewOnly());
        var view = remoteSessionService.start(id, viewOnly, currentActor());
        return ResponseEntity.status(HttpStatus.CREATED).body(RemoteSessionResponse.withTicket(view));
    }

    /**
     * Stop a session. <b>204</b>, idempotent — stopping an already-terminal session is a 204,
     * not an error, so a double-click never surfaces a failure.
     */
    @Operation(summary = "[SENSITIVE] Stop a remote view/control session")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Stopped (or already terminal)"),
            @ApiResponse(responseCode = "403", description = "Caller is VIEWER/ADVERTISER"),
            @ApiResponse(responseCode = "404", description = "Unknown session, or it belongs to another device")
    })
    @SensitiveEndpoint
    @DeleteMapping("/{sessionKey}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<Void> stop(@PathVariable Long id, @PathVariable String sessionKey) {
        deviceManagementService.assertScopeForDevice(id);
        remoteSessionService.stop(id, sessionKey, currentActor());
        return ResponseEntity.noContent().build();
    }

    /**
     * The device's current non-terminal session, <b>without</b> {@code viewerTicket} — tickets
     * are single-issue, so a reconnecting viewer must {@code POST} again. 404 when there is none.
     */
    @Operation(summary = "Current remote session for a device")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The live session (no viewerTicket)"),
            @ApiResponse(responseCode = "403", description = "Caller is VIEWER/ADVERTISER"),
            @ApiResponse(responseCode = "404", description = "No live session for this device")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<RemoteSessionResponse> current(@PathVariable Long id) {
        deviceManagementService.assertScopeForDevice(id);
        return remoteSessionService.current(id)
                .map(view -> ResponseEntity.ok(RemoteSessionResponse.withoutTicket(view)))
                // The id in this message is the DEVICE id, not a session id — there is no session
                // to name. Spelling it out keeps the 404 body from reading like a bad session key.
                .orElseThrow(() -> new ResourceNotFoundException("Remote session for device", id));
    }

    /**
     * Device-side ack — REST rather than an inbound WebSocket frame on purpose: the device sends
     * no outbound WS frames today ({@code ANDROID_DEVICE_FLOW_SPEC} §10.5), and adding an inbound
     * path to {@code DeviceWebSocketHandler} is a far bigger change than one endpoint. Reuses
     * {@code X-Device-Token} and the existing {@code /actions/{actionId}/confirm} precedent.
     */
    @Operation(summary = "Device acknowledges a remote session (READY / FAILED / ENDED)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ack recorded"),
            @ApiResponse(responseCode = "403", description = "Token belongs to a different device"),
            @ApiResponse(responseCode = "404", description = "Unknown session, or it belongs to another device"),
            @ApiResponse(responseCode = "409", description = "Session is already terminal")
    })
    @PostMapping("/{sessionKey}/ack")
    @PreAuthorize("hasRole('DEVICE') and #id == authentication.principal")
    public ResponseEntity<AckResponse> ack(@PathVariable Long id,
                                            @PathVariable String sessionKey,
                                            @Valid @RequestBody RemoteSessionAckRequest request) {
        var view = remoteSessionService.ack(id, sessionKey, request.status(),
                request.width(), request.height(), request.error(), request.reason());
        return ResponseEntity.ok(new AckResponse(view.sessionId(), view.status()));
    }

    /** JWT subject, or {@code "system"} when there is somehow no authentication in context. */
    private static String currentActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getName() != null ? auth.getName() : "system";
    }

    // ----- DTOs -----

    /** All fields optional; an absent body means a full (non-view-only) session. */
    public record StartRemoteSessionRequest(Boolean viewOnly) {}

    /**
     * {@code status} is required and constrained to the enum, so an unknown value is a 400 from
     * Jackson rather than a silently-ignored ack. {@code error} is expected when the device
     * reports {@code FAILED}; {@code reason} when it reports {@code ENDED}.
     */
    public record RemoteSessionAckRequest(
            @NotNull(message = "status is required") AckStatus status,
            Integer width,
            Integer height,
            String error,
            String reason) {}

    public record AckResponse(String sessionId, String status) {}

    /**
     * Operator-facing session body. {@code viewerTicket} is present only on the {@code POST}
     * response — {@link #withoutTicket(RemoteSessionView)} nulls it for {@code GET} so a session
     * read can never hand out fresh credentials.
     */
    public record RemoteSessionResponse(String sessionId, Long deviceId, String status,
                                         String relayUrl, String viewerTicket, Instant expiresAt,
                                         boolean viewOnly, String deliveredVia,
                                         CapabilityDto capability) {

        static RemoteSessionResponse withTicket(RemoteSessionView v) {
            return from(v, v.viewerTicket());
        }

        static RemoteSessionResponse withoutTicket(RemoteSessionView v) {
            return from(v, null);
        }

        private static RemoteSessionResponse from(RemoteSessionView v, String ticket) {
            return new RemoteSessionResponse(v.sessionId(), v.deviceId(), v.status(), v.relayUrl(),
                    ticket, v.expiresAt(), v.viewOnly(), v.deliveredVia(),
                    CapabilityDto.from(v.capability()));
        }
    }

    /** Last capability the device reported; {@code null} when it never has. */
    public record CapabilityDto(Boolean supported, String input, String transport,
                                 Integer maxWidth, Integer maxHeight, Instant reportedAt) {
        static CapabilityDto from(RemoteCapabilityView c) {
            return c == null ? null : new CapabilityDto(c.supported(), c.input(), c.transport(),
                    c.maxWidth(), c.maxHeight(), c.reportedAt());
        }
    }
}
