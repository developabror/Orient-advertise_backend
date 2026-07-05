package uz.orientadvertise.services.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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
import uz.orientadvertise.services.api.dto.DeviceGroupDetail;
import uz.orientadvertise.services.api.dto.DeviceGroupSummary;
import uz.orientadvertise.services.api.dto.SetVolumeRequest;
import uz.orientadvertise.services.api.openapi.SensitiveEndpoint;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.service.BulkRemoteActionService;
import uz.orientadvertise.services.service.BulkRemoteActionService.BulkActionResult;
import uz.orientadvertise.services.service.DeviceGroupManagementService;
import uz.orientadvertise.services.service.DeviceGroupManagementService.DeviceGroupDetailView;
import uz.orientadvertise.services.service.DeviceManagementService;

/**
 * Device group CRUD plus the bulk-action issue endpoint.
 *
 * <p>The duplicate guards (create + rename) match the DB-level
 * {@code UNIQUE (project_id, name)} constraint (V37 migration,
 * {@code uq_device_group_name_per_project}). {@code DELETE} is ADMIN-only and is refused
 * with 409 either when active devices still reference the group or when a CONFIRMED
 * {@code ContentAssignment} targets it — see
 * {@link DeviceGroupManagementService#softDelete} for the disambiguation.
 *
 * <p>The bulk-action endpoint at {@code POST /{id}/actions} predates the CRUD set and is
 * left untouched: a backward-compatible addition.
 */
@Tag(name = "Devices", description = "Device group CRUD and bulk actions")
@RestController
@RequestMapping("/api/device-groups")
public class DeviceGroupController {

    private final BulkRemoteActionService bulkRemoteActionService;
    private final DeviceGroupManagementService managementService;
    private final DeviceManagementService deviceManagementService;

    public DeviceGroupController(BulkRemoteActionService bulkRemoteActionService,
                                   DeviceGroupManagementService managementService,
                                   DeviceManagementService deviceManagementService) {
        this.bulkRemoteActionService = bulkRemoteActionService;
        this.managementService = managementService;
        this.deviceManagementService = deviceManagementService;
    }

    /**
     * Project a group detail view, enriching each member with its heartbeat-derived
     * {@code computedStatus} (from {@code device_status_view}) rather than the raw column.
     */
    private DeviceGroupDetail toDetail(DeviceGroupDetailView view) {
        var ids = view.devices().stream().map(Device::getId).toList();
        return DeviceGroupDetail.from(view, deviceManagementService.computedStatuses(ids),
                deviceManagementService.effectiveVolumes(ids));
    }

    // ----- CRUD -----

    @Operation(summary = "List device groups (filtered, paginated)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of device groups (possibly empty)"),
            @ApiResponse(responseCode = "400", description = "Page size > 100"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<Page<DeviceGroupSummary>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String name,
            Pageable pageable) {
        return ResponseEntity.ok(
                managementService.list(projectId, name, pageable).map(DeviceGroupSummary::from));
    }

    @Operation(summary = "Device group detail (with member device list)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Group detail"),
            @ApiResponse(responseCode = "404", description = "Unknown id, or soft-deleted")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<DeviceGroupDetail> detail(@PathVariable Long id) {
        return ResponseEntity.ok(toDetail(managementService.getDetail(id)));
    }

    @Operation(summary = "Create a device group")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Project not found"),
            @ApiResponse(responseCode = "409", description = "Duplicate (project_id, name)")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<DeviceGroupDetail> create(@Valid @RequestBody CreateDeviceGroupRequest req) {
        var view = managementService.create(req.projectId(), req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(view));
    }

    @Operation(summary = "Rename a device group")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Renamed"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Unknown id"),
            @ApiResponse(responseCode = "409", description = "Duplicate (project_id, name) for the new name")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<DeviceGroupDetail> rename(@PathVariable Long id,
                                                      @Valid @RequestBody RenameDeviceGroupRequest req) {
        var view = managementService.rename(id, req.name());
        return ResponseEntity.ok(toDetail(view));
    }

    @Operation(summary = "[SENSITIVE] Soft-delete a device group")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Soft-deleted"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN"),
            @ApiResponse(responseCode = "404", description = "Unknown id, or already soft-deleted"),
            @ApiResponse(responseCode = "409",
                    description = "Group has active devices, or is targeted by CONFIRMED assignments")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        managementService.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    // ----- membership -----

    @Operation(
            summary = "Add devices to a group",
            description = "Sets device.deviceGroup to this group for each id. Devices already "
                    + "in this group are no-ops; devices in a different group are moved silently "
                    + "and the previous group id is reported in `movedFrom` for audit."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Membership applied",
                    content = @Content(schema = @Schema(implementation = AddDevicesResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "addedCount": 2,
                                      "alreadyMember": [101],
                                      "movedFrom": { "202": 7, "203": 9 }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Devices belong to a different project"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Group missing/soft-deleted, or one or more devices missing/soft-deleted")
    })
    @PostMapping("/{id}/devices")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<AddDevicesResponse> addDevices(@PathVariable Long id,
                                                          @Valid @RequestBody AddDevicesRequest req) {
        var result = managementService.addDevices(id, req.deviceIds());
        return ResponseEntity.ok(new AddDevicesResponse(
                result.addedCount(), result.alreadyMember(), result.movedFrom()));
    }

    @Operation(
            summary = "Remove a device from this group",
            description = "Sets device.deviceGroup = null. Removing the last device is allowed; "
                    + "empty groups remain valid targets for assignment preview/setup."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Group/device missing, or device is not in this group")
    })
    @DeleteMapping("/{id}/devices/{deviceId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<Void> removeDevice(@PathVariable Long id, @PathVariable Long deviceId) {
        managementService.removeDevice(id, deviceId);
        return ResponseEntity.noContent().build();
    }

    // ----- volume -----

    @Operation(
            summary = "Set the group's volume",
            description = "Members with no per-device override inherit this on their next heartbeat "
                    + "(no fan-out write — that's the inheritance model). Surfaced on the group detail "
                    + "as `volume`."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Group volume set"),
            @ApiResponse(responseCode = "400", description = "volume out of range [0,100]"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Group missing/soft-deleted, or out of operator scope")
    })
    @PutMapping("/{id}/volume")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<Void> setVolume(@PathVariable Long id,
                                          @Valid @RequestBody SetVolumeRequest req) {
        managementService.setVolume(id, req.volume());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Clear the group's volume",
            description = "Members fall back to their per-device override or the default (100) on "
                    + "their next heartbeat."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Group volume cleared"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Group missing/soft-deleted, or out of operator scope")
    })
    @DeleteMapping("/{id}/volume")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<Void> clearVolume(@PathVariable Long id) {
        managementService.clearVolume(id);
        return ResponseEntity.noContent().build();
    }

    // ----- bulk actions (unchanged contract) -----

    @PostMapping("/{id}/actions")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<BulkActionResponse> issueAction(@PathVariable Long id,
                                                           @Valid @RequestBody BulkActionRequest request) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        var issuedBy = auth != null ? auth.getName() : "system";

        BulkActionResult result = bulkRemoteActionService.issueToGroup(
                id, request.actionType(), request.payload(), issuedBy);

        // Even with partial failures, return 200 with the summary (not 5xx).
        return ResponseEntity.ok(BulkActionResponse.from(result));
    }

    public record CreateDeviceGroupRequest(
            @NotNull Long projectId,
            @NotBlank @Size(max = 100) String name) {}

    public record RenameDeviceGroupRequest(
            @NotBlank @Size(max = 100) String name) {}

    public record AddDevicesRequest(
            @NotEmpty List<Long> deviceIds) {}

    /**
     * Membership-add response. {@code addedCount} is the number of devices that actually
     * landed in this group (excludes already-member); {@code alreadyMember} lists devices
     * the caller asked for but that were already attached; {@code movedFrom} records the
     * previous group id for any device that was silently moved out of a different group.
     */
    @Schema(name = "AddDevicesResponse",
            description = "Result of POST /api/device-groups/{id}/devices")
    public record AddDevicesResponse(
            @Schema(description = "Number of devices newly attached to this group "
                    + "(excludes already-member rows)", example = "2")
            int addedCount,
            @Schema(description = "Device ids the caller asked for that were already attached "
                    + "to this group (no-op)", example = "[101]")
            List<Long> alreadyMember,
            @Schema(description = "For devices silently moved out of a different group, maps "
                    + "deviceId -> previousGroupId. Empty when no devices were moved.",
                    example = "{ \"202\": 7, \"203\": 9 }")
            Map<Long, Long> movedFrom) {}

    public record BulkActionRequest(
            @NotBlank(message = "actionType is required") String actionType,
            String payload
    ) {}

    public record BulkActionResponse(
            Long deviceGroupId,
            String actionType,
            int totalDevices,
            int succeededCount,
            int skippedCount,
            int failedCount,
            java.util.List<Long> succeededActionIds,
            java.util.List<BulkRemoteActionService.DeviceFailure> skipped,
            java.util.List<BulkRemoteActionService.DeviceFailure> failed
    ) {
        public static BulkActionResponse from(BulkActionResult r) {
            return new BulkActionResponse(
                    r.deviceGroupId(), r.actionType(), r.totalDevices(),
                    r.succeededCount(), r.skippedCount(), r.failedCount(),
                    r.succeededActionIds(), r.skipped(), r.failed());
        }
    }
}
