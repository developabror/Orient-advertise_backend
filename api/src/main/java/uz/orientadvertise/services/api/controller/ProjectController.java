package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.api.dto.ProjectDetail;
import uz.orientadvertise.services.api.dto.ProjectSummary;
import uz.orientadvertise.services.api.openapi.SensitiveEndpoint;
import uz.orientadvertise.services.domain.model.ProjectOperator;
import uz.orientadvertise.services.service.ProjectManagementService;
import uz.orientadvertise.services.service.ProjectOperatorService;

/**
 * Project CRUD — root of the org tree (project → region → facility → device). Mutations
 * are ADMIN-only; the listing and detail are open to {@code OPERATOR}/{@code VIEWER} so
 * non-admins can navigate the tree.
 *
 * <p>Duplicate guard on {@code name} matches the DB UNIQUE constraint
 * {@code uq_project_name} (V30 migration). {@code DELETE} runs a single 409 guard
 * (project must have zero regions) — see {@link ProjectManagementService#delete}.
 */
@Tag(name = "Devices", description = "Project CRUD (org tree level 1)")
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectManagementService service;
    private final ProjectOperatorService projectOperatorService;

    public ProjectController(ProjectManagementService service,
                            ProjectOperatorService projectOperatorService) {
        this.service = service;
        this.projectOperatorService = projectOperatorService;
    }

    @Operation(
            summary = "List all projects",
            description = "Unpaginated by design — projects are a small set in practice "
                    + "(single-digit to low-double-digit). Returns a flat ordered list."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of projects (possibly empty)"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<List<ProjectSummary>> list() {
        var summaries = service.list().stream().map(ProjectSummary::from).toList();
        return ResponseEntity.ok(summaries);
    }

    @Operation(summary = "Project detail (with immediate child regions and device groups)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project detail"),
            @ApiResponse(responseCode = "404", description = "Unknown id")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<ProjectDetail> detail(@PathVariable Long id) {
        return ResponseEntity.ok(ProjectDetail.from(service.getDetail(id)));
    }

    @Operation(summary = "[SENSITIVE] Create a project")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN"),
            @ApiResponse(responseCode = "409", description = "Duplicate name")
    })
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProjectDetail> create(@Valid @RequestBody CreateProjectRequest req) {
        var view = service.create(req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectDetail.from(view));
    }

    @Operation(summary = "[SENSITIVE] Rename a project")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Renamed"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN"),
            @ApiResponse(responseCode = "404", description = "Unknown id"),
            @ApiResponse(responseCode = "409", description = "Duplicate name")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProjectDetail> rename(@PathVariable Long id,
                                                  @Valid @RequestBody RenameProjectRequest req) {
        var view = service.rename(id, req.name());
        return ResponseEntity.ok(ProjectDetail.from(view));
    }

    @Operation(
            summary = "[SENSITIVE] Hard-delete a project",
            description = "Refuses with 409 if any region or device group still belongs to this "
                    + "project (two sequential guards: regions, then device groups). Operators must "
                    + "remove the project's regions (with their facilities / devices) and its device "
                    + "groups first. Once empty, the row is removed permanently — projects are not soft-deleted."
    )
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN"),
            @ApiResponse(responseCode = "404", description = "Unknown id"),
            @ApiResponse(responseCode = "409", description = "Project still has regions or device groups")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // --- Operator ↔ project assignment (ADMIN-only sub-resource). The project create/rename
    //     DTOs are unchanged — operators are managed only through these endpoints.

    @Operation(summary = "[SENSITIVE] List operators assigned to a project")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assigned operators (possibly empty)"),
            @ApiResponse(responseCode = "404", description = "Unknown project")
    })
    @GetMapping("/{projectId}/operators")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<List<OperatorRef>> listOperators(@PathVariable Long projectId) {
        var refs = projectOperatorService.listOperators(projectId).stream().map(OperatorRef::from).toList();
        return ResponseEntity.ok(refs);
    }

    @Operation(summary = "[SENSITIVE] Set the whole operator set for a project (bulk add/remove)")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Full resulting operator set"),
            @ApiResponse(responseCode = "404", description = "Unknown project or user"),
            @ApiResponse(responseCode = "409", description = "A userId is not an OPERATOR")
    })
    @PutMapping("/{projectId}/operators")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<OperatorRef>> setOperators(@PathVariable Long projectId,
                                                          @RequestBody SetOperatorsRequest req) {
        var assignedBy = currentUsername();
        var refs = projectOperatorService
                .setOperators(projectId, req == null ? List.of() : req.userIds(), assignedBy)
                .stream().map(OperatorRef::from).toList();
        return ResponseEntity.ok(refs);
    }

    @Operation(summary = "[SENSITIVE] Assign a single operator to a project")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Assigned"),
            @ApiResponse(responseCode = "404", description = "Unknown project or user"),
            @ApiResponse(responseCode = "409", description = "Duplicate, or user is not an OPERATOR")
    })
    @PostMapping("/{projectId}/operators/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> assignOperator(@PathVariable Long projectId, @PathVariable Long userId) {
        projectOperatorService.assignOperator(projectId, userId, currentUsername());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "[SENSITIVE] Unassign an operator from a project (idempotent)")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Unassigned (or was not assigned)"),
            @ApiResponse(responseCode = "404", description = "Unknown project")
    })
    @DeleteMapping("/{projectId}/operators/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unassignOperator(@PathVariable Long projectId, @PathVariable Long userId) {
        projectOperatorService.unassignOperator(projectId, userId);
        return ResponseEntity.noContent().build();
    }

    private static String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }

    public record CreateProjectRequest(
            @NotBlank @Size(max = 100) String name) {}

    public record RenameProjectRequest(
            @NotBlank @Size(max = 100) String name) {}

    public record SetOperatorsRequest(List<Long> userIds) {}

    public record OperatorRef(Long userId, String username, Instant assignedAt, String assignedBy) {
        public static OperatorRef from(ProjectOperator po) {
            return new OperatorRef(po.getUser().getId(), po.getUser().getUsername(),
                    po.getAssignedAt(), po.getAssignedBy());
        }
    }
}
