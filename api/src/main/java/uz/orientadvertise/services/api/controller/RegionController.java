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
import uz.orientadvertise.services.api.dto.RegionDetail;
import uz.orientadvertise.services.api.dto.RegionSummary;
import uz.orientadvertise.services.api.openapi.SensitiveEndpoint;
import uz.orientadvertise.services.service.RegionManagementService;

/**
 * Region CRUD. Regions sit at level 2 of the org tree: project → <b>region</b> → facility
 * → device. Device groups are no longer a child of the region — they belong to the project
 * (V37). Per the org-tree contract regions are <b>not</b> soft-deleted — see the README's
 * "Org Tree" section for the rationale.
 *
 * <p>Duplicate guard on {@code (project_id, code)} matches the DB UNIQUE constraint
 * {@code uq_region_code_per_project} (V4 migration). {@code DELETE} is ADMIN-only and
 * runs two sequential 409 guards before the actual delete — see
 * {@link RegionManagementService#delete} for the order and rationale.
 */
@Tag(name = "Devices", description = "Region CRUD (org tree level 2)")
@RestController
@RequestMapping("/api/regions")
public class RegionController {

    private final RegionManagementService service;

    public RegionController(RegionManagementService service) {
        this.service = service;
    }

    @Operation(summary = "List regions (filtered, paginated)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of regions (possibly empty)"),
            @ApiResponse(responseCode = "400", description = "Page size > 100"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<Page<RegionSummary>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String name,
            Pageable pageable) {
        return ResponseEntity.ok(service.list(projectId, name, pageable).map(RegionSummary::from));
    }

    @Operation(summary = "Region detail (with facility list)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Region detail"),
            @ApiResponse(responseCode = "404", description = "Unknown id")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<RegionDetail> detail(@PathVariable Long id) {
        return ResponseEntity.ok(RegionDetail.from(service.getDetail(id)));
    }

    @Operation(summary = "Create a region")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Project not found"),
            @ApiResponse(responseCode = "409", description = "Duplicate (project_id, code)")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<RegionDetail> create(@Valid @RequestBody CreateRegionRequest req) {
        var view = service.create(req.projectId(), req.code(), req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(RegionDetail.from(view));
    }

    @Operation(
            summary = "Rename or recode a region",
            description = "Both fields are optional — submit only the one you want to change. "
                    + "A request body with both null is a no-op and returns the unchanged detail."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed (blank code/name)"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Unknown id"),
            @ApiResponse(responseCode = "409", description = "Duplicate (project_id, code)")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<RegionDetail> update(@PathVariable Long id,
                                                 @Valid @RequestBody UpdateRegionRequest req) {
        var view = service.update(id, req.code(), req.name());
        return ResponseEntity.ok(RegionDetail.from(view));
    }

    @Operation(
            summary = "[SENSITIVE] Hard-delete a region",
            description = "Two sequential 409 guards: active devices, then facilities. "
                    + "Regions are not soft-deleted — once a region passes every guard, the "
                    + "row is removed permanently."
    )
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN"),
            @ApiResponse(responseCode = "404", description = "Unknown id"),
            @ApiResponse(responseCode = "409",
                    description = "Region still has active devices or facilities")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateRegionRequest(
            @NotNull Long projectId,
            @NotBlank @Size(max = 20) String code,
            @NotBlank @Size(max = 100) String name) {}

    /**
     * PATCH-style partial update body. Either field may be {@code null} (leave unchanged);
     * a non-null but blank string is rejected with 400 by the service. {@code @Size}
     * still applies when the value is non-null.
     */
    public record UpdateRegionRequest(
            @Size(max = 20) String code,
            @Size(max = 100) String name) {}
}
