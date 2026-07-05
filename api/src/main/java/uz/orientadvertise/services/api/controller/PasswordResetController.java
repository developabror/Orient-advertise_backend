package uz.orientadvertise.services.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.service.PasswordResetRateLimiter;
import uz.orientadvertise.services.service.PasswordService;

/**
 * Public, unauthenticated password recovery — lives under {@code /api/auth/**} (already
 * {@code permitAll} in {@code SecurityConfig}).
 *
 * <p><b>No account enumeration:</b> {@code POST /forgot-password} always answers 202 with the
 * same body whether or not the email matches a user. The reset/validate endpoints are
 * IP-rate-limited (the token is 256-bit, so this is defence-in-depth against brute force).
 */
@Tag(name = "Auth", description = "Public password recovery: forgot / reset / validate")
@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

    private final PasswordService passwordService;
    private final PasswordResetRateLimiter rateLimiter;

    public PasswordResetController(PasswordService passwordService, PasswordResetRateLimiter rateLimiter) {
        this.passwordService = passwordService;
        this.rateLimiter = rateLimiter;
    }

    @Operation(
            summary = "Request a password-reset link",
            description = "If an active account owns the email, a one-time reset link is emailed. "
                    + "The response is identical (202) whether or not the email exists — no account "
                    + "enumeration. Rate-limited per IP and per email."
    )
    @SecurityRequirements // public — clears the global bearerAuth requirement
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted (always, regardless of whether the email exists)"),
            @ApiResponse(responseCode = "400", description = "Malformed email"),
            @ApiResponse(responseCode = "429", description = "Rate limited")
    })
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                               HttpServletRequest httpRequest) {
        passwordService.requestReset(request.email(), clientIp(httpRequest));
        return ResponseEntity.accepted().build();
    }

    @Operation(summary = "Reset a password using a one-time token")
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Password reset; all sessions revoked"),
            @ApiResponse(responseCode = "400", description = "Bad/expired/used token, mismatch, or policy"),
            @ApiResponse(responseCode = "429", description = "Rate limited")
    })
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                              HttpServletRequest httpRequest) {
        rateLimiter.checkResetAllowed(clientIp(httpRequest));
        passwordService.resetPassword(request.token(), request.newPassword(), request.confirmPassword());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Check whether a reset token is still valid",
            description = "Non-consuming. Public token oracle, so it is rate-limited the same as the "
                    + "reset POST to blunt brute force (the 256-bit token makes guessing infeasible regardless)."
    )
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "{ valid: boolean }"),
            @ApiResponse(responseCode = "429", description = "Rate limited")
    })
    @GetMapping("/reset-password")
    public ResponseEntity<ValidateTokenResponse> validateToken(@RequestParam String token,
                                                               HttpServletRequest httpRequest) {
        rateLimiter.checkResetAllowed(clientIp(httpRequest));
        return ResponseEntity.ok(new ValidateTokenResponse(passwordService.isResetTokenValid(token)));
    }

    /** Best-effort client IP — first X-Forwarded-For hop when present, else the socket address. */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record ForgotPasswordRequest(@NotBlank @Email String email) {}

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 8, max = 200) String newPassword,
            @NotBlank String confirmPassword) {}

    public record ValidateTokenResponse(boolean valid) {}
}
