package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.service.AdvertiserContentService;
import uz.orientadvertise.services.service.OperatorContentService;
import uz.orientadvertise.services.service.UserManagementService;

/**
 * Admin-only user lifecycle and advertiser-content link/unlink endpoints.
 *
 * <p>Linking is restricted to the {@code ADVERTISER} role — the access table only makes
 * sense for that role; ADMIN/OPERATOR/VIEWER see all content via their broader privileges.
 */
@Tag(name = "Admin", description = "ADMIN-only user lifecycle and advertiser-content linking")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserManagementService userManagementService;
    private final AdvertiserContentService advertiserContentService;
    private final OperatorContentService operatorContentService;

    public UserController(UserManagementService userManagementService,
                           AdvertiserContentService advertiserContentService,
                           OperatorContentService operatorContentService) {
        this.userManagementService = userManagementService;
        this.advertiserContentService = advertiserContentService;
        this.operatorContentService = operatorContentService;
    }

    /**
     * Paginated user list for the admin dashboard. Spring Data's {@link Pageable} binds
     * {@code page}, {@code size}, and {@code sort} from query params automatically —
     * pagination is <b>0-indexed</b> here to match the rest of the API
     * (e.g. {@code /api/devices}). Frontends sending {@code page=1} get the second page,
     * not the first.
     *
     * <p>Includes deactivated rows so the admin UI can render and manage them; the
     * {@code active} flag on each result distinguishes them.
     */
    @Operation(summary = "[SENSITIVE] List users (paginated)")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of users (possibly empty)"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN")
    })
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<UserResponse>> list(Pageable pageable) {
        Page<AppUser> page = userManagementService.list(pageable);
        return ResponseEntity.ok(page.map(UserResponse::from));
    }

    /**
     * Detail projection for a single user. Soft-deleted (deactivated) users return 404 —
     * the list endpoint is the only way to see them, since the admin UI needs to render
     * deactivated rows as disabled.
     */
    @Operation(summary = "[SENSITIVE] Get user detail")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User detail",
                    content = @Content(examples = @ExampleObject(value = """
                            {
                              "id": 50,
                              "username": "alice",
                              "role": "ADVERTISER",
                              "active": true,
                              "createdAt": "2026-01-15T10:30:00Z"
                            }
                            """))),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN or OPERATOR"),
            @ApiResponse(responseCode = "404", description = "Unknown or soft-deleted user")
    })
    @GetMapping("/{userId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<UserDetailResponse> get(@PathVariable Long userId) {
        var user = userManagementService.getById(userId);
        return ResponseEntity.ok(UserDetailResponse.from(user));
    }

    @Operation(summary = "[SENSITIVE] Create a user with role")
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User created",
                    content = @Content(examples = @ExampleObject(value = """
                            { "id": 50, "username": "alice", "role": "ADVERTISER", "active": true }
                            """))),
            @ApiResponse(responseCode = "400", description = "Validation failed (username, password, role)"),
            @ApiResponse(responseCode = "403", description = "Caller is not ADMIN"),
            @ApiResponse(responseCode = "409", description = "Username already exists")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples =
            @ExampleObject(value = """
                    { "username": "alice", "password": "s3cret-passphrase", "role": "ADVERTISER" }
                    """)))
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        var user = userManagementService.create(request.username(), request.password(),
                request.role(), request.email());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    /**
     * Edge case: deleting an advertiser cascades all of their content access grants. The
     * cascade runs in the same transaction as the user delete — a failure rolls both back.
     */
    @Operation(
            summary = "[SENSITIVE] Delete a user (cascades advertiser content access)",
            description = "Removes the user. For ADVERTISERs, every linked content access "
                    + "grant is also removed in the same transaction. Irreversible."
    )
    @SensitiveEndpoint
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "404", description = "Unknown user")
    })
    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long userId) {
        userManagementService.delete(userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * List content linked to an advertiser. Edge case: zero-linked returns an empty array,
     * not 404 — the advertiser dashboard renders "no content yet" without a special branch.
     */
    @GetMapping("/{userId}/content")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<List<LinkedContent>> listLinkedContent(@PathVariable Long userId) {
        var content = advertiserContentService.getAccessibleContent(userId);
        return ResponseEntity.ok(content.stream().map(LinkedContent::from).toList());
    }

    @PostMapping("/{userId}/content/{contentFileId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> linkContent(@PathVariable Long userId,
                                             @PathVariable Long contentFileId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var grantedBy = auth != null ? auth.getName() : "unknown";
        advertiserContentService.linkContent(userId, contentFileId, grantedBy);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * Idempotent — unlinking a non-existent grant returns 204 either way. Avoids 404 churn
     * for the admin UI, which fires DELETE on every visible row whether or not it's still
     * linked server-side.
     */
    @DeleteMapping("/{userId}/content/{contentFileId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unlinkContent(@PathVariable Long userId,
                                                @PathVariable Long contentFileId) {
        advertiserContentService.unlinkContent(userId, contentFileId);
        return ResponseEntity.noContent().build();
    }

    // --- Operator content grants (ADMIN-only) — mirror of the advertiser endpoints in DTO/shape,
    //     but NOT in auth: grant management is an admin-only surface. The GET deliberately does
    //     NOT copy `OPERATOR` from listLinkedContent above, or an operator could read any user's grants.

    /** List content an operator was granted. ADMIN-only. Zero grants → empty array (never 404 for an existing user). */
    @GetMapping("/{userId}/operator-content")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public ResponseEntity<List<LinkedContent>> listOperatorContent(@PathVariable Long userId) {
        var content = operatorContentService.getAccessibleContent(userId);
        return ResponseEntity.ok(content.stream().map(LinkedContent::from).toList());
    }

    @PostMapping("/{userId}/operator-content/{contentFileId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> linkOperatorContent(@PathVariable Long userId,
                                                     @PathVariable Long contentFileId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        var grantedBy = auth != null ? auth.getName() : "unknown";
        operatorContentService.linkContent(userId, contentFileId, grantedBy);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** Idempotent — unlinking a non-existent grant still returns 204. */
    @DeleteMapping("/{userId}/operator-content/{contentFileId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unlinkOperatorContent(@PathVariable Long userId,
                                                       @PathVariable Long contentFileId) {
        operatorContentService.unlinkContent(userId, contentFileId);
        return ResponseEntity.noContent().build();
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 100) String username,
            @NotBlank @Size(min = 6, max = 200) String password,
            @NotNull Role role,
            // Optional recovery email (the FE create-user modal already collects it). Validated
            // only when present; null/blank means "no email".
            @Email String email) {}

    public record UserResponse(Long id, String username, Role role, boolean active, String email) {
        public static UserResponse from(AppUser user) {
            return new UserResponse(user.getId(), user.getUsername(), user.getRole(),
                    user.isActive(), user.getEmail());
        }
    }

    public record UserDetailResponse(Long id, String username, Role role, boolean active,
                                     Instant createdAt, String email) {
        public static UserDetailResponse from(AppUser user) {
            return new UserDetailResponse(
                    user.getId(),
                    user.getUsername(),
                    user.getRole(),
                    user.isActive(),
                    user.getCreatedAt(),
                    user.getEmail());
        }
    }

    public record LinkedContent(Long id, String name, String status) {
        public static LinkedContent from(ContentFile file) {
            return new LinkedContent(file.getId(), file.getName(), file.getStatus().name());
        }
    }
}
