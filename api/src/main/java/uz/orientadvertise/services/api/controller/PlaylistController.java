package uz.orientadvertise.services.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import uz.orientadvertise.services.api.dto.PlaylistDetail;
import uz.orientadvertise.services.api.dto.PlaylistItemDto;
import uz.orientadvertise.services.api.dto.PlaylistSummary;
import uz.orientadvertise.services.api.openapi.SensitiveEndpoint;
import uz.orientadvertise.services.service.PlaylistItemService;
import uz.orientadvertise.services.service.PlaylistManagementService;

/**
 * Playlist CRUD. The two duplicate guards (create + rename) match the DB-level
 * {@code UNIQUE (project_id, name)} constraint — see the README's <i>Playlist
 * Management</i> section. {@code DELETE} is ADMIN-only and refuses while any active
 * (non-DRAFT, non-CANCELLED) assignment still references the playlist; details on the
 * 409 message format are in {@link PlaylistManagementService#softDelete}.
 */
@Tag(name = "Content", description = "Playlist CRUD")
@RestController
@RequestMapping("/api/playlists")
public class PlaylistController {

    private final PlaylistManagementService service;
    private final PlaylistItemService itemService;

    public PlaylistController(PlaylistManagementService service,
                               PlaylistItemService itemService) {
        this.service = service;
        this.itemService = itemService;
    }

    @Operation(summary = "List playlists (filtered, paginated)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of playlists (possibly empty)"),
            @ApiResponse(responseCode = "400", description = "Page size > 100"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role")
    })
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<Page<PlaylistSummary>> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String name,
            Pageable pageable) {
        return ResponseEntity.ok(service.list(projectId, name, pageable).map(PlaylistSummary::from));
    }

    @Operation(summary = "Playlist detail (with ordered items)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Playlist detail"),
            @ApiResponse(responseCode = "404", description = "Unknown id, or soft-deleted")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public ResponseEntity<PlaylistDetail> detail(@PathVariable Long id) {
        return ResponseEntity.ok(PlaylistDetail.from(service.getDetail(id)));
    }

    @Operation(summary = "Create a playlist")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Project not found"),
            @ApiResponse(responseCode = "409", description = "Duplicate (project_id, name)")
    })
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<PlaylistDetail> create(@Valid @RequestBody CreatePlaylistRequest req) {
        var view = service.create(req.projectId(), req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(PlaylistDetail.from(view));
    }

    @Operation(summary = "Rename a playlist")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Renamed"),
            @ApiResponse(responseCode = "400", description = "Validation failed"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Unknown id"),
            @ApiResponse(responseCode = "409", description = "Duplicate (project_id, name) for the new name")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<PlaylistDetail> rename(@PathVariable Long id,
                                                  @Valid @RequestBody RenamePlaylistRequest req) {
        var view = service.rename(id, req.name());
        return ResponseEntity.ok(PlaylistDetail.from(view));
    }

    @Operation(summary = "[SENSITIVE] Soft-delete a playlist")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Soft-deleted"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN"),
            @ApiResponse(responseCode = "404", description = "Unknown id, or already soft-deleted"),
            @ApiResponse(responseCode = "409", description = "Playlist is in use by N active assignment(s)")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    // ----- item operations -----

    @Operation(summary = "Add an item to a playlist")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Item appended (or inserted at position)"),
            @ApiResponse(responseCode = "400", description = "Validation failed; non-READY content; out-of-range position"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Playlist or content file missing/soft-deleted")
    })
    @PostMapping("/{id}/items")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<PlaylistItemDto> addItem(@PathVariable Long id,
                                                     @Valid @RequestBody AddItemRequest req) {
        var item = itemService.addItem(id, req.contentFileId(), req.position(), req.durationSeconds());
        return ResponseEntity.status(HttpStatus.CREATED).body(PlaylistItemDto.from(item));
    }

    @Operation(summary = "Remove an item from a playlist (positions compact afterward)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed; subsequent positions compacted"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Playlist/item not found, or item is not in this playlist")
    })
    @DeleteMapping("/{id}/items/{itemId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<Void> removeItem(@PathVariable Long id, @PathVariable Long itemId) {
        itemService.removeItem(id, itemId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Move an item to a new position (sentinel-pattern atomic shift)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Moved; returns the moved item"),
            @ApiResponse(responseCode = "400", description = "toPosition out of range"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Playlist or item not found")
    })
    @PutMapping("/{id}/items/{itemId}/move")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<PlaylistItemDto> moveItem(@PathVariable Long id,
                                                     @PathVariable Long itemId,
                                                     @Valid @RequestBody MoveItemRequest req) {
        var item = itemService.moveItem(id, itemId, req.toPosition());
        return ResponseEntity.ok(PlaylistItemDto.from(item));
    }

    @Operation(
            summary = "Set or clear an item's duration override",
            description = "Body: { durationSeconds: int|null }. Non-null sets the override. "
                    + "Null clears it so device-side /playlist falls back to the file's "
                    + "natural duration (content_file.duration_seconds)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated; returns the item"),
            @ApiResponse(responseCode = "400", description = "durationSeconds out of [1, 86400]"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Playlist or item not found / soft-deleted")
    })
    @PutMapping("/{id}/items/{itemId}/duration")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<PlaylistItemDto> setItemDuration(@PathVariable Long id,
                                                            @PathVariable Long itemId,
                                                            @Valid @RequestBody SetDurationRequest req) {
        var item = itemService.setDuration(id, itemId, req.durationSeconds());
        return ResponseEntity.ok(PlaylistItemDto.from(item));
    }

    @Operation(summary = "Bulk-reorder items by an exact ordered ID list")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reordered; returns the new ordered list"),
            @ApiResponse(responseCode = "400", description = "orderedItemIds does not exactly match current item set"),
            @ApiResponse(responseCode = "403", description = "Caller lacks ADMIN/OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Playlist not found")
    })
    @PutMapping("/{id}/items/reorder")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<List<PlaylistItemDto>> reorderItems(@PathVariable Long id,
                                                                @Valid @RequestBody ReorderItemsRequest req) {
        var items = itemService.reorderAll(id, req.orderedItemIds());
        return ResponseEntity.ok(items.stream().map(PlaylistItemDto::from).toList());
    }

    public record CreatePlaylistRequest(
            @NotNull Long projectId,
            @NotBlank @Size(max = 200) String name) {}

    public record RenamePlaylistRequest(
            @NotBlank @Size(max = 200) String name) {}

    public record AddItemRequest(
            @NotNull Long contentFileId,
            Integer position,
            Integer durationSeconds) {}

    public record MoveItemRequest(
            @NotNull @Min(0) Integer toPosition) {}

    public record ReorderItemsRequest(
            @NotNull @NotEmpty List<Long> orderedItemIds) {}

    /**
     * {@code durationSeconds} is intentionally nullable — null clears the override so the
     * device-side {@code /playlist} response falls back to the source content file's
     * natural duration. Bean Validation's {@code @Min}/{@code @Max} only kick in when the
     * value is non-null, which is exactly what we want.
     */
    public record SetDurationRequest(
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true,
                    description = "Duration override in seconds (1..86400); null clears the override "
                            + "so the device falls back to the source file's natural duration")
            @Min(1) @Max(86400) Integer durationSeconds) {}
}
