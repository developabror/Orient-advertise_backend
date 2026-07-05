package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.api.openapi.SensitiveEndpoint;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.DeviceActionType;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.service.DeviceActionService;
import uz.orientadvertise.services.service.DeviceManagementService;

/**
 * External integration endpoints. Authenticated via {@code X-API-Key} only — JWT
 * principals do not have {@code ROLE_API_CLIENT}, so a stolen JWT cannot reach these
 * endpoints, and a stolen API key cannot reach the JWT-only endpoints.
 *
 * <p><b>Public identifiers only.</b> The integration sees devices by serial number, never
 * by internal database id. Action and event responses include the serial and the public
 * timestamp/type fields but never {@code deviceId}, {@code userId}, or any other
 * row-level primary key. This is enforced at the DTO boundary (see
 * {@link DeviceStatusResponse}, {@link EventEntry}, {@link ExternalActionResponse}).
 */
@Tag(name = "External", description = "Partner integration endpoints — X-API-Key auth, 100 req/hr per key")
@SecurityRequirement(name = "apiKeyAuth")
@RestController
@RequestMapping("/api/external/devices")
public class ExternalDeviceController {

    public static final int MAX_HISTORY_PAGE_SIZE = 100;
    public static final int DEFAULT_HISTORY_PAGE_SIZE = 50;

    private final DeviceRepository deviceRepository;
    private final EventRepository eventRepository;
    private final DeviceActionService deviceActionService;
    private final DeviceManagementService deviceManagementService;

    public ExternalDeviceController(DeviceRepository deviceRepository,
                                      EventRepository eventRepository,
                                      DeviceActionService deviceActionService,
                                      DeviceManagementService deviceManagementService) {
        this.deviceRepository = deviceRepository;
        this.eventRepository = eventRepository;
        this.deviceActionService = deviceActionService;
        this.deviceManagementService = deviceManagementService;
    }

    @Operation(
            summary = "Get current device status",
            description = "Returns the device's current operational state, last heartbeat, "
                    + "and active content version. Identifies the device by serial number — "
                    + "internal database ids are never exposed."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Device status",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "serialNumber": "TVB-00042",
                              "name": "Lobby Display",
                              "computedStatus": "ONLINE",
                              "lastHeartbeatAt": "2026-05-06T10:30:00Z",
                              "currentContentVersion": "v42-content-bundle"
                            }
                            """))),
            @ApiResponse(responseCode = "401", description = "Missing or revoked X-API-Key"),
            @ApiResponse(responseCode = "404", description = "Unknown serial number"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @GetMapping("/{serialNumber}/status")
    @PreAuthorize("hasRole('API_CLIENT')")
    public ResponseEntity<DeviceStatusResponse> getStatus(
            @Parameter(description = "Public device serial — never the internal id",
                    example = "TVB-00042")
            @PathVariable String serialNumber) {
        var device = deviceRepository.findBySerialNumberAndDeletedAtIsNull(serialNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Device", serialNumber));
        var computed = deviceManagementService.computedStatus(device.getId());
        return ResponseEntity.ok(new DeviceStatusResponse(
                device.getSerialNumber(),
                device.getName(),
                computed != null ? computed.name() : null,
                device.getLastHeartbeatAt(),
                device.getCurrentContentVersion()));
    }

    @Operation(
            summary = "Get device event history",
            description = "Paginated event log filtered by date range. Page size capped at "
                    + MAX_HISTORY_PAGE_SIZE + ". Default window is the trailing 7 days when "
                    + "`from`/`to` are omitted."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Event history page",
                    content = @Content(examples = @ExampleObject(value = """
                            [
                              {
                                "occurredAt": "2026-05-06T10:30:00Z",
                                "eventType": "OFFLINE",
                                "priority": "HIGH",
                                "payload": "{\\"reason\\":\\"network-timeout\\"}"
                              }
                            ]
                            """))),
            @ApiResponse(responseCode = "400", description = "Invalid range or page size"),
            @ApiResponse(responseCode = "404", description = "Unknown serial"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @GetMapping("/{serialNumber}/history")
    @PreAuthorize("hasRole('API_CLIENT')")
    public ResponseEntity<List<EventEntry>> getHistory(
            @PathVariable String serialNumber,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_HISTORY_PAGE_SIZE) int size) {
        if (size > MAX_HISTORY_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page size cannot exceed " + MAX_HISTORY_PAGE_SIZE);
        }
        var device = deviceRepository.findBySerialNumberAndDeletedAtIsNull(serialNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Device", serialNumber));

        Instant resolvedTo = to != null ? to : Instant.now();
        Instant resolvedFrom = from != null ? from : resolvedTo.minusSeconds(86400L * 7);
        Pageable pageable = PageRequest.of(page, size);
        var events = eventRepository.findFiltered(
                device.getId(), null, null, resolvedFrom, resolvedTo, null, pageable);

        var entries = events.getContent().stream()
                .map(e -> new EventEntry(
                        e.getOccurredAt(),
                        e.getEventType(),
                        e.getPriority() != null ? e.getPriority().name() : null))
                .toList();
        return ResponseEntity.ok(entries);
    }

    @Operation(
            summary = "[SENSITIVE] Issue a remote action to a device",
            description = "Mutates production state on a remote device. Audit-logged; the "
                    + "API key prefix is recorded as the issuer (never the internal id). "
                    + "Bean validation rejects volume out of [0, 100] before reaching the service."
    )
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Action queued",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "actionId": 7,
                              "serialNumber": "TVB-00042",
                              "actionType": "REBOOT",
                              "status": "PENDING",
                              "issuedAt": "2026-05-06T10:30:00Z",
                              "expiresAt": "2026-05-06T11:30:00Z"
                            }
                            """))),
            @ApiResponse(responseCode = "400", description = "Volume out of range, missing type"),
            @ApiResponse(responseCode = "404", description = "Unknown serial"),
            @ApiResponse(responseCode = "409", description = "Pending-action queue full or duplicate type"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
            @ExampleObject(name = "reboot", value = """
                    { "type": "REBOOT" }
                    """),
            @ExampleObject(name = "set-volume-50", value = """
                    { "type": "VOLUME_SET", "volume": 50 }
                    """)
    }))
    @PostMapping("/{serialNumber}/actions")
    @PreAuthorize("hasRole('API_CLIENT')")
    public ResponseEntity<ExternalActionResponse> issueAction(
            @PathVariable String serialNumber,
            @Valid @RequestBody ExternalActionRequest request) {
        var device = deviceRepository.findBySerialNumberAndDeletedAtIsNull(serialNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Device", serialNumber));

        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        // The principal is the public 8-char key prefix (set by ApiKeyAuthFilter), never
        // an internal user id. Audit trails record this prefix as "issuedBy".
        String issuedBy = "apikey:" + (auth != null ? auth.getName() : "unknown");

        var action = deviceActionService.issueAction(
                device.getId(), request.type(), request.volume(), issuedBy);

        return ResponseEntity.status(HttpStatus.CREATED).body(new ExternalActionResponse(
                action.getId(),
                device.getSerialNumber(),
                action.getActionType(),
                action.getStatus().name(),
                action.getIssuedAt(),
                action.getExpiresAt()));
    }

    /**
     * Returns the device's current state. Internal id is intentionally absent — external
     * integrations identify devices by serial number only.
     */
    public record DeviceStatusResponse(
            String serialNumber,
            String name,
            String computedStatus,
            Instant lastHeartbeatAt,
            String currentContentVersion) {}

    // Raw Event.payload is intentionally NOT exposed to partners (it can carry internal
    // ids / diagnostic detail). Only the public occurredAt / eventType / priority are shared.
    public record EventEntry(
            Instant occurredAt,
            String eventType,
            String priority) {}

    public record ExternalActionRequest(
            @NotNull(message = "type is required") DeviceActionType type,
            @Min(value = 0, message = "volume must be in [0, 100]")
            @Max(value = 100, message = "volume must be in [0, 100]")
            Integer volume) {}

    /**
     * {@code actionId} is exposed because the integration may want to correlate it with a
     * later confirmation webhook — but no internal device id, user id, or issuer username
     * appears here. The caller already knows the serial number it sent.
     */
    public record ExternalActionResponse(
            Long actionId,
            String serialNumber,
            String actionType,
            String status,
            Instant issuedAt,
            Instant expiresAt) {}
}
