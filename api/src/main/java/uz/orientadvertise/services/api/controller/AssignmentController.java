package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.api.openapi.SensitiveEndpoint;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentAssignment.TargetType;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;
import uz.orientadvertise.services.service.ContentAssignmentService;
import uz.orientadvertise.services.service.ContentAssignmentService.PreviewResult;

@RestController
@RequestMapping("/api/assignments")
public class AssignmentController {

    private final ContentAssignmentService assignmentService;
    private final PlaylistRepository playlistRepository;

    public AssignmentController(ContentAssignmentService assignmentService,
                                 PlaylistRepository playlistRepository) {
        this.assignmentService = assignmentService;
        this.playlistRepository = playlistRepository;
    }

    /**
     * Create a DRAFT assignment. Drafts auto-expire after 1 hour if not confirmed.
     *
     * <p><b>Scheduling tip:</b> for assignments that should apply whenever a device
     * next reconnects (including devices that are currently offline), use a
     * far-future {@code endTime}. The resolver requires
     * {@code startTime ≤ now AND endTime > now}; a short window that elapses while
     * a device is offline resolves to no active assignment on reconnect, and the
     * device is told to delete the content. "Forever until cancelled" is therefore
     * an open-ended {@code endTime}, not a missing one — pick a sentinel like
     * year 2100 or the playback campaign's real cut-off.
     */
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Draft created"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "404", description = "Unknown playlist"),
            @ApiResponse(responseCode = "409",
                    description = "The playlist has no items. (Time overlap is NOT checked at draft "
                            + "creation — it is enforced device-aware at confirm.)")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<AssignmentResponse> createDraft(@Valid @RequestBody CreateDraftRequest request) {
        Playlist playlist = playlistRepository.findByIdAndDeletedAtIsNull(request.playlistId())
                .orElseThrow(() -> new ResourceNotFoundException("Playlist", request.playlistId()));

        var draft = assignmentService.createDraft(playlist, request.targetType(), request.targetId(),
                request.startTime(), request.endTime());

        return ResponseEntity.status(201).body(AssignmentResponse.from(draft));
    }

    /**
     * Confirm a draft, atomically attaching device exclusions. The DB transaction
     * spans both the status flip AND the exclusion inserts — partial commit is impossible.
     *
     * <p>A time-overlap re-check runs against existing CONFIRMED assignments at confirmation
     * time — even if the draft was created without conflict, another CONFIRMED assignment
     * for the same target may have appeared in the meantime. Conflicts return <b>409</b>
     * with the conflicting assignments (ids + UTC time windows) in the structured
     * {@code details.conflicts} field — not in the free-text message; the draft remains in
     * DRAFT state so the caller can retry after resolving the conflict.
     */
    @Operation(summary = "Confirm a draft assignment with optional device exclusions or inclusions",
            description = "Send `excludedDeviceIds` to confirm against the full target scope minus those devices. "
                    + "Send `includedDeviceIds` to confirm against ONLY those devices (the backend derives the "
                    + "complement set as exclusions — useful when a target has more than the 200-device preview "
                    + "cap and the FE can't enumerate the full set client-side). The two fields are mutually "
                    + "exclusive: sending both returns 400.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assignment confirmed"),
            @ApiResponse(responseCode = "400",
                    description = "Both excludedDeviceIds and includedDeviceIds set, or includedDeviceIds "
                            + "contains ids outside the assignment target scope"),
            @ApiResponse(responseCode = "404", description = "Unknown assignment or excluded device"),
            @ApiResponse(responseCode = "409",
                    description = "Time overlap with an existing CONFIRMED assignment (retry with "
                            + "replaceConflicting:true to supersede it — see details.conflicts), "
                            + "the assignment is not in DRAFT status, or the playlist has no items")
    })
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<AssignmentResponse> confirm(@PathVariable Long id,
                                                       @Valid @RequestBody ConfirmRequest request) {
        var excluded = Optional.ofNullable(request.excludedDeviceIds()).orElse(List.of());
        var included = Optional.ofNullable(request.includedDeviceIds()).orElse(List.of());

        if (!excluded.isEmpty() && !included.isEmpty()) {
            throw new IllegalArgumentException(
                    "Provide either excludedDeviceIds or includedDeviceIds, not both");
        }

        var confirmed = included.isEmpty()
                ? assignmentService.confirmWithExclusions(id, excluded, request.reason(), request.replaceConflicting())
                : assignmentService.confirmWithIncludedDevices(id, included, request.reason(), request.replaceConflicting());
        return ResponseEntity.ok(AssignmentResponse.from(confirmed));
    }

    /**
     * Cancel (soft-delete) an assignment so operators can retarget without DB access — the
     * prerequisite for the year-2100 "forever" replace pattern. The target's devices
     * re-resolve on their next heartbeat/sync (and, for a CONFIRMED row, within ~1s via the
     * after-commit cancel push). No status flip is needed: soft-delete frees the overlap
     * window and stops resolution because both queries filter {@code deletedAt IS NULL}.
     */
    @Operation(summary = "[SENSITIVE] Cancel (soft-delete) a content assignment")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cancelled; target devices re-resolve on next sync"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Unknown id, or already cancelled")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        assignmentService.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Preview the devices that would be affected by an assignment to a target.
     * Capped at 200 devices; total count is always reported.
     * Offline devices are included with an {@code offline} flag set.
     */
    @GetMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'VIEWER')")
    public ResponseEntity<PreviewResult> preview(
            @RequestParam @NotNull TargetType targetType,
            @RequestParam @NotNull Long targetId) {
        var result = assignmentService.previewForTarget(targetType, targetId, Instant.now());
        return ResponseEntity.ok(result);
    }

    public record CreateDraftRequest(
            @NotNull Long playlistId,
            @NotNull TargetType targetType,
            @NotNull Long targetId,
            @NotNull Instant startTime,
            @NotNull Instant endTime
    ) {}

    /**
     * {@code replaceConflicting} (default false): when the confirm-time overlap re-check finds an
     * existing CONFIRMED assignment on the same target, true retires it (supersede) and confirms
     * this one atomically; false returns 409 with the conflict {@code details} so the FE can offer
     * a "Replace existing &amp; assign" action that re-drives confirm with the flag set.
     */
    public record ConfirmRequest(List<Long> excludedDeviceIds,
                                  List<Long> includedDeviceIds,
                                  String reason,
                                  boolean replaceConflicting) {}

    public record AssignmentResponse(
            Long id, Long playlistId, String targetType, Long targetId,
            int priority, Instant startTime, Instant endTime,
            String status, Instant createdAt
    ) {
        public static AssignmentResponse from(ContentAssignment a) {
            return new AssignmentResponse(
                    a.getId(),
                    a.getPlaylist() != null ? a.getPlaylist().getId() : null,
                    a.getTargetType().name(),
                    a.getTargetId(),
                    a.getPriority(),
                    a.getStartTime(),
                    a.getEndTime(),
                    a.getStatus().name(),
                    a.getCreatedAt());
        }
    }
}
