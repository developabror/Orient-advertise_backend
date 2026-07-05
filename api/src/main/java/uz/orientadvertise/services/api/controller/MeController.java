package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

/**
 * Profile of the currently-authenticated user. Distinct from {@code /api/users/{id}} —
 * this endpoint never accepts an id, so it can never leak another user's record, and any
 * authenticated role may call it (admins, operators, viewers, advertisers all need to
 * render their own header / settings page).
 *
 * <p>Returns a deliberately narrow projection ({@link MeResponse}) — the password hash,
 * refresh tokens, and any other sensitive fields on {@link AppUser} are not exposed.
 * The corresponding test asserts the hash is never present in the response body.
 */
@Tag(name = "Auth", description = "Current user profile")
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AppUserRepository userRepository;
    private final OperatorScopeResolver operatorScopeResolver;

    public MeController(AppUserRepository userRepository,
                        OperatorScopeResolver operatorScopeResolver) {
        this.userRepository = userRepository;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    @Operation(
            summary = "Return the authenticated user's profile",
            description = "Resolves the principal from the JWT and returns the matching active user row."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated user's profile"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or stale JWT")
    })
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MeResponse> me() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // @PreAuthorize already gated this on isAuthenticated(), so a null/blank principal
        // here means the security context was cleared mid-request (deactivation, etc.) —
        // surface as 401 via AuthenticationException, never 404.
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new AuthenticationException("Authentication context missing");
        }
        var user = userRepository.findByUsernameAndIsActiveTrue(auth.getName())
                .orElseThrow(() -> new AuthenticationException("Authenticated user not found"));
        // assignedProjectIds is populated only for OPERATOR (via the resolver, NOT a direct
        // repository — keeps the controller off the domain.repository layer). All other roles
        // get an empty list, which the FE treats as "unrestricted".
        List<Long> assignedProjectIds = List.of();
        if (user.getRole() == Role.OPERATOR) {
            ScopedProjects scope = operatorScopeResolver.resolveForUsername(user.getUsername());
            if (scope.projectIds() != null) {
                assignedProjectIds = scope.projectIds();
            }
        }
        return ResponseEntity.ok(MeResponse.from(user, assignedProjectIds));
    }

    public record MeResponse(Long id, String username, Role role, boolean active, Instant createdAt,
                             String email, List<Long> assignedProjectIds) {
        // email and assignedProjectIds are ALWAYS present (email may be null, list defaults to
        // []) — deliberately NOT @JsonInclude(NON_NULL): the FE parses "always present".
        public static MeResponse from(AppUser user, List<Long> assignedProjectIds) {
            return new MeResponse(
                    user.getId(),
                    user.getUsername(),
                    user.getRole(),
                    user.isActive(),
                    user.getCreatedAt(),
                    user.getEmail(),
                    assignedProjectIds);
        }
    }
}
