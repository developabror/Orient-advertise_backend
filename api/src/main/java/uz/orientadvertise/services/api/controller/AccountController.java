package uz.orientadvertise.services.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.service.PasswordService;

/**
 * Authenticated self-service account management — change own password, set/clear recovery
 * email. Lives under {@code /api/me/**} (covered by {@code .anyRequest().authenticated()}).
 * Shares the {@code /api/me} base path with {@code MeController} ({@code GET /api/me}); no
 * mapping collision since the sub-paths differ.
 *
 * <p>The principal is resolved from the {@link SecurityContextHolder} exactly like
 * {@code MeController} — a null/blank principal surfaces as 401, never 404.
 */
@Tag(name = "Auth", description = "Authenticated self-service: change password, set recovery email")
@RestController
@RequestMapping("/api/me")
public class AccountController {

    private final PasswordService passwordService;

    public AccountController(PasswordService passwordService) {
        this.passwordService = passwordService;
    }

    @Operation(summary = "Change the authenticated user's password",
            description = "Verifies the current password, applies the new one, and revokes ALL sessions. "
                    + "The caller must re-login afterward.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password changed; all sessions revoked"),
            @ApiResponse(responseCode = "400", description = "Wrong current password, mismatch, or policy violation"),
            @ApiResponse(responseCode = "401", description = "Missing, invalid, or stale JWT")
    })
    @PostMapping("/password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        passwordService.changeOwnPassword(currentUsername(),
                request.currentPassword(), request.newPassword(), request.confirmPassword());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Set or clear the authenticated user's recovery email",
            description = "A blank/null email clears it. The address is used by forgot-password.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Email set or cleared"),
            @ApiResponse(responseCode = "400", description = "Invalid email format"),
            @ApiResponse(responseCode = "409", description = "Email already in use by another account")
    })
    @PutMapping("/email")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> setEmail(@Valid @RequestBody SetEmailRequest request) {
        passwordService.setOwnEmail(currentUsername(), request.email());
        return ResponseEntity.noContent().build();
    }

    private static String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // @PreAuthorize already gated on isAuthenticated(); a null/blank principal here means
        // the context was cleared mid-request — surface as 401, never 404.
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new AuthenticationException("Authentication context missing");
        }
        return auth.getName();
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 200) String newPassword,
            @NotBlank String confirmPassword) {}

    /** Blank/null email clears the recovery address; a non-blank value must be a valid email. */
    public record SetEmailRequest(@Email String email) {}
}
