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
import jakarta.validation.constraints.PositiveOrZero;
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
import uz.orientadvertise.services.api.dto.SyncGroupDetail;
import uz.orientadvertise.services.api.dto.SyncGroupJumpResult;
import uz.orientadvertise.services.api.dto.SyncGroupPlaybackView;
import uz.orientadvertise.services.api.dto.SyncGroupSummary;
import uz.orientadvertise.services.api.openapi.SensitiveEndpoint;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.service.DeviceManagementService;
import uz.orientadvertise.services.service.SyncGroupManagementService;
import uz.orientadvertise.services.service.SyncGroupManagementService.SyncGroupDetailView;
import uz.orientadvertise.services.service.SyncGroupPlaybackService;

/**
 * SyncGroup CRUD + membership — the project-scoped "sales point" grouping the frontend manages
 * at {@code /settings/sync-groups}. Modeled on {@link DeviceGroupController} minus volume, minus
 * the bulk-action endpoint, minus the {@code description} field, and with a single delete guard
 * (a sync group can never be a {@code ContentAssignment} target, so only the member-device guard
 * applies). Delete is a HARD delete.
 *
 * <p>Every method carries an explicit {@code @PreAuthorize}: the catch-all
 * {@code .anyRequest().authenticated()} rule also admits {@code ROLE_DEVICE} /
 * {@code ROLE_API_CLIENT} principals, so a token role must never be able to manage sync groups.
 * Operator scope (V35) is enforced service-side; out-of-scope always collapses to 404.
 */
@Tag(name = "Sync Groups", description = "SyncGroup CRUD and membership (synchronized-playback sales points)")
@RestController
@RequestMapping("/api/sync-groups")
public class SyncGroupController {

    private final SyncGroupManagementService managementService;
    private final DeviceManagementService deviceManagementService;
    private final SyncGroupPlaybackService playbackService;

    public SyncGroupController(SyncGroupManagementService managementService,
                               DeviceManagementService deviceManagementService,
                               SyncGroupPlaybackService playbackService) {
        this.managementService = managementService;
        this.deviceManagementService = deviceManagementService;
        this.playbackService = playbackService;
    }

    /**
     * Project a detail view, enriching each member with its heartbeat-derived computed status
     * (from {@code device_status_view}) rather than the raw column. The member field is named
     * {@code status} (not {@code computedStatus}) — the FE detail response renders {@code d.status}.
     */
    private SyncGroupDetail toDetail(SyncGroupDetailView view) {
        var ids = view.devices().stream().map(Device::getId).toList();
        return SyncGroupDetail.from(view, deviceManagementService.computedStatuses(ids));
    }

    // ----- CRUD -----

    @Operation(summary = "List sync groups (filtered, paginated)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of sync groups (possibly empty)"),
            @ApiResponse(responseCode = "400", description = "Page size > 100"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<Page<SyncGroupSummary>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String name,
            Pageable pageable) {
        return ResponseEntity.ok(
                managementService.list(projectId, name, pageable).map(SyncGroupSummary::from));
    }

    @Operation(summary = "Sync group detail (with member device list)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Group detail"),
            @ApiResponse(responseCode = "404", description = "Unknown id, or out of operator scope")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<SyncGroupDetail> detail(@PathVariable Long id) {
        return ResponseEntity.ok(toDetail(managementService.getDetail(id)));
    }

    @Operation(summary = "Create a sync group")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Project not found"),
            @ApiResponse(responseCode = "409", description = "Duplicate (project_id, name)")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<SyncGroupDetail> create(@Valid @RequestBody CreateSyncGroupRequest req) {
        var view = managementService.create(req.projectId(), req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(view));
    }

    @Operation(summary = "Rename a sync group")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Renamed"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Unknown id, or out of operator scope"),
            @ApiResponse(responseCode = "409", description = "Duplicate (project_id, name) for the new name")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<SyncGroupDetail> rename(@PathVariable Long id,
                                                  @Valid @RequestBody RenameSyncGroupRequest req) {
        var view = managementService.rename(id, req.name());
        return ResponseEntity.ok(toDetail(view));
    }

    @Operation(summary = "[SENSITIVE] Delete a sync group")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted (hard delete)"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN"),
            @ApiResponse(responseCode = "404", description = "Unknown id, or out of operator scope"),
            @ApiResponse(responseCode = "409", description = "Group still has active member devices")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        managementService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ----- membership -----

    @Operation(
            summary = "Set the members of a sync group",
            description = "Sets device.syncGroup to this group for each id. Devices already in this "
                    + "group are no-ops; devices in a different sync group are moved silently and the "
                    + "previous sync-group id is reported in `movedFrom` for audit."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Membership applied",
                    content = @Content(schema = @Schema(implementation = AddDevicesResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "addedCount": 1,
                                      "alreadyMember": [102],
                                      "movedFrom": { "103": 2 }
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Devices belong to a different project"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Group out of scope, or one or more devices missing/soft-deleted")
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
            summary = "Remove a device from this sync group",
            description = "Sets device.syncGroup = null (the device returns to the project's unassigned "
                    + "pool). Removing the last device is allowed; empty sync groups remain valid."
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

    // ----- synchronized playback (group jump) -----

    @Operation(
            summary = "Sync-group playback view (pickable items + active jump)",
            description = "The shared deliverable timeline the whole group can jump to. When the "
                    + "members are not content-coherent (different playlists/versions, a member with "
                    + "no active playlist, or an empty group) `coherent` is false with a `reason` and "
                    + "an empty item list."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Playback view (coherent, or coherent:false + reason)"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR/VIEWER"),
            @ApiResponse(responseCode = "404", description = "Unknown id, or out of operator scope")
    })
    @GetMapping("/{id}/playback")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<SyncGroupPlaybackView> playback(@PathVariable Long id) {
        return ResponseEntity.ok(SyncGroupPlaybackView.from(playbackService.getPlaybackView(id)));
    }

    @Operation(
            summary = "Jump every device in the sync group to a playlist index",
            description = "Re-anchors the group's loop so all members converge on `index` at a "
                    + "coordinated cut-over and stay frame-aligned. Delivered over the existing /sync "
                    + "wire; offline members converge on their next heartbeat. A re-jump overwrites "
                    + "the active jump."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Jump scheduled; members re-anchored"),
            @ApiResponse(responseCode = "400", description = "index out of range [0, deliverableCount)"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Unknown id, or out of operator scope"),
            @ApiResponse(responseCode = "409", description = "Group empty or not content-coherent")
    })
    @PostMapping("/{id}/playback/jump")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<SyncGroupJumpResult> jump(@PathVariable Long id,
                                                    @Valid @RequestBody JumpRequest req) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        var issuedBy = auth != null ? auth.getName() : "system";
        return ResponseEntity.ok(
                SyncGroupJumpResult.from(playbackService.jumpToIndex(id, req.index(), issuedBy)));
    }

    public record CreateSyncGroupRequest(
            @NotNull Long projectId,
            @NotBlank @Size(max = 100) String name) {}

    public record RenameSyncGroupRequest(
            @NotBlank @Size(max = 100) String name) {}

    public record AddDevicesRequest(
            @NotEmpty List<Long> deviceIds) {}

    /** Jump target: the 0-based deliverable index every member converges on. */
    public record JumpRequest(
            @NotNull @PositiveOrZero Integer index) {}

    /**
     * Membership response (reuses the device-group envelope). {@code addedCount} is the number of
     * devices newly attached (excludes already-member); {@code alreadyMember} lists ids that were
     * already in this group; {@code movedFrom} maps deviceId → previous <b>sync</b>-group id for
     * any device silently moved out of a different sync group.
     */
    @Schema(name = "SyncGroupAddDevicesResponse",
            description = "Result of POST /api/sync-groups/{id}/devices")
    public record AddDevicesResponse(
            @Schema(description = "Number of devices newly attached to this sync group "
                    + "(excludes already-member rows)", example = "1")
            int addedCount,
            @Schema(description = "Device ids the caller asked for that were already in this sync group",
                    example = "[102]")
            List<Long> alreadyMember,
            @Schema(description = "For devices silently moved out of a different sync group, maps "
                    + "deviceId -> previousSyncGroupId. Empty when no devices were moved.",
                    example = "{ \"103\": 2 }")
            Map<Long, Long> movedFrom) {}
}
