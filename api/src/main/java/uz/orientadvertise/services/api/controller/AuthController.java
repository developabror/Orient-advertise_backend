package uz.orientadvertise.services.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.orientadvertise.services.api.advice.GlobalExceptionHandler;
import uz.orientadvertise.services.api.security.RefreshTokenCookie;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.domain.auth.AuthToken;
import uz.orientadvertise.services.domain.auth.LoginRequest;
import uz.orientadvertise.services.service.AuthService;
import uz.orientadvertise.services.service.LoginRateLimiter;

@Tag(name = "Auth", description = "JWT login / refresh / logout")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthService authService, RefreshTokenCookie refreshTokenCookie,
                          LoginRateLimiter loginRateLimiter) {
        this.authService = authService;
        this.refreshTokenCookie = refreshTokenCookie;
        this.loginRateLimiter = loginRateLimiter;
    }

    @Operation(
            summary = "Authenticate and receive an access token",
            description = """
                    Validates credentials, then returns a short-lived JWT access token in the \
                    response body and issues a long-lived refresh token as an HttpOnly cookie \
                    (`refresh_token`, scoped to `/api/auth`, `SameSite` configurable, default \
                    `Strict`). The refresh token is never exposed to JavaScript. Reject reasons: \
                    invalid credentials (401), deactivated account (401), no role assigned (401).
                    """
    )
    @SecurityRequirements // public endpoint — clears the global bearerAuth requirement
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated; access token in body, refresh token in `refresh_token` cookie",
                    content = @Content(schema = @Schema(implementation = AccessTokenResponse.class),
                            examples = @ExampleObject(name = "ok", value = """
                                    { "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIi..." }
                                    """))),
            @ApiResponse(responseCode = "401", description = "Invalid credentials, deactivated account, or no role assigned",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @RequestBody(required = true,
            content = @Content(examples = @ExampleObject(name = "credentials", value = """
                    { "username": "alice", "password": "s3cret-passphrase" }
                    """)))
    @PostMapping("/login")
    public ResponseEntity<AccessTokenResponse> login(
            @Valid @org.springframework.web.bind.annotation.RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String clientIp = clientIp(httpRequest);
        // 429 if the source IP is over its attempt budget or the account is locked.
        loginRateLimiter.checkAllowed(clientIp, request.username());
        AuthToken token;
        try {
            token = authService.login(request);
        } catch (AuthenticationException e) {
            loginRateLimiter.recordFailure(request.username());
            throw e;
        }
        loginRateLimiter.recordSuccess(request.username());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.issue(token.refreshToken()).toString())
                .body(new AccessTokenResponse(token.accessToken()));
    }

    /** Best-effort client IP — first X-Forwarded-For hop when present, else the socket address. */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @Operation(
            summary = "Rotate the refresh cookie and mint a fresh access token",
            description = """
                    Single-use refresh: the supplied cookie value is invalidated and replaced. \
                    Reuse of a previously rotated id is treated as a possible replay and \
                    invalidates the entire token family (every refresh token issued under \
                    the same login session). Role changes take effect here — the new access \
                    token is minted with the user's CURRENT role. The refresh token is read \
                    from the `refresh_token` cookie; a fresh cookie is set on the response.
                    """
    )
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New access token in body, rotated refresh token in cookie",
                    content = @Content(schema = @Schema(implementation = AccessTokenResponse.class))),
            @ApiResponse(responseCode = "401", description = "Cookie missing, token unknown, expired, or replay detected",
                    content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
    })
    @PostMapping("/refresh")
    public ResponseEntity<AccessTokenResponse> refresh(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken,
            HttpServletRequest httpRequest) {
        loginRateLimiter.checkRefreshAllowed(clientIp(httpRequest));
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthenticationException("Missing refresh cookie");
        }
        var token = authService.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.issue(token.refreshToken()).toString())
                .body(new AccessTokenResponse(token.accessToken()));
    }

    @Operation(
            summary = "Invalidate a refresh-token family (logout)",
            description = "Reads the `refresh_token` cookie, invalidates every refresh token "
                    + "in its family, and clears the cookie on the response. Returns 204 even "
                    + "when the cookie is missing or the token is unknown — no information leak."
    )
    @SecurityRequirements
    @ApiResponses(@ApiResponse(responseCode = "204", description = "Logged out (or token already invalid)"))
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logout(refreshToken);
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear().toString())
                .build();
    }

    @Schema(description = "Access-token response body. The refresh token is delivered separately as an HttpOnly cookie.")
    public record AccessTokenResponse(
            @Schema(description = "Short-lived JWT access token. Send as `Authorization: Bearer <token>`.",
                    example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIi...")
            String accessToken) {}
}
