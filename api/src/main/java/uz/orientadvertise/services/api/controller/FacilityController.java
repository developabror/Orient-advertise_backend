package uz.orientadvertise.services.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import uz.orientadvertise.services.api.dto.FacilityDetail;
import uz.orientadvertise.services.api.dto.FacilitySummary;
import uz.orientadvertise.services.api.openapi.SensitiveEndpoint;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.service.DeviceManagementService;
import uz.orientadvertise.services.service.FacilityManagementService;
import uz.orientadvertise.services.service.FacilityManagementService.FacilityDetailView;

/**
 * Facility CRUD. Mirrors {@link RegionController} structurally; sits at level 3 of the
 * org tree (project → region → <b>facility</b> → device). Per the org-tree contract,
 * facilities are <b>not</b> soft-deleted — see the README's "Org Tree" section.
 *
 * <p>Duplicate guard on {@code (region_id, name)} matches the DB UNIQUE constraint
 * {@code uq_facility_name_per_region} (V4 migration). {@code DELETE} is ADMIN-only and
 * runs two sequential 409 guards (active devices, then CONFIRMED assignments) before
 * the actual delete.
 */
@Tag(name = "Devices", description = "Facility CRUD (org tree level 3)")
@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    private final FacilityManagementService service;
    private final DeviceManagementService deviceManagementService;

    public FacilityController(FacilityManagementService service,
                              DeviceManagementService deviceManagementService) {
        this.service = service;
        this.deviceManagementService = deviceManagementService;
    }

    /**
     * Project a facility detail view, enriching each member with its heartbeat-derived
     * {@code computedStatus} (from {@code device_status_view}) rather than the raw column.
     */
    private FacilityDetail toDetail(FacilityDetailView view) {
        var ids = view.devices().stream().map(Device::getId).toList();
        return FacilityDetail.from(view, deviceManagementService.computedStatuses(ids),
                deviceManagementService.effectiveVolumes(ids));
    }

    @Operation(summary = "List facilities (filtered, paginated)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of facilities (possibly empty)"),
            @ApiResponse(responseCode = "400", description = "Page size > 100"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<Page<FacilitySummary>> list(
            @RequestParam(required = false) Long regionId,
            @RequestParam(required = false) String name,
            Pageable pageable) {
        return ResponseEntity.ok(service.list(regionId, name, pageable).map(FacilitySummary::from));
    }

    @Operation(summary = "Facility detail (with active member device list)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Facility detail"),
            @ApiResponse(responseCode = "404", description = "Unknown id")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<FacilityDetail> detail(@PathVariable Long id) {
        return ResponseEntity.ok(toDetail(service.getDetail(id)));
    }

    @Operation(summary = "Create a facility")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Region not found"),
            @ApiResponse(responseCode = "409", description = "Duplicate (region_id, name)")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<FacilityDetail> create(@Valid @RequestBody CreateFacilityRequest req) {
        var view = service.create(req.regionId(), req.name(), req.address());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(view));
    }

    @Operation(
            summary = "Update a facility (rename and/or change address, within its existing region)",
            description = "PATCH-style body: every field is independently optional. "
                    + "`null` means \"leave unchanged\"; non-null but blank is rejected with 400 "
                    + "because the underlying columns reject silent corruption. Cross-region "
                    + "moves are NOT supported here — moving a facility between regions touches "
                    + "device FKs and assignment targets, which are outside the scope of an update. "
                    + "To move a facility, reassign its devices, hard-delete the facility, and "
                    + "create a new one in the target region."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed (e.g., blank name or address)"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Unknown id"),
            @ApiResponse(responseCode = "409", description = "Duplicate (region_id, name) for the new name")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<FacilityDetail> update(@PathVariable Long id,
                                                   @Valid @RequestBody UpdateFacilityRequest req) {
        var view = service.update(id, req.name(), req.address());
        return ResponseEntity.ok(toDetail(view));
    }

    @Operation(
            summary = "[SENSITIVE] Hard-delete a facility",
            description = "Two sequential 409 guards: active devices, then CONFIRMED assignments. "
                    + "Facilities are not soft-deleted — once a facility passes both guards, "
                    + "the row is removed permanently."
    )
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN"),
            @ApiResponse(responseCode = "404", description = "Unknown id"),
            @ApiResponse(responseCode = "409",
                    description = "Facility still has active devices, or is targeted by CONFIRMED assignments")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateFacilityRequest(
            @NotNull Long regionId,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String address) {}

    /**
     * PATCH-style update: both fields independently nullable. Mirrors the validation
     * contract of {@code UpdateRegionRequest} — null means "leave unchanged" so partial
     * payloads work; the service rejects non-null-but-blank with 400 to match the DB
     * NOT NULL on {@code name} (and to keep {@code address} from being silently
     * corrupted to whitespace).
     */
    public record UpdateFacilityRequest(
            @Size(max = 100) String name,
            @Size(max = 500) String address) {}
}
