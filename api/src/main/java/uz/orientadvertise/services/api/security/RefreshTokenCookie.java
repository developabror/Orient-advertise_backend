package uz.orientadvertise.services.api.security;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the refresh-token cookie used by {@code /api/auth/login},
 * {@code /api/auth/refresh}, and {@code /api/auth/logout}.
 *
 * <p>Centralising the attributes here keeps the three endpoints from drifting:
 * {@code HttpOnly} prevents JS from reading the token (XSS containment),
 * {@code Secure} forces TLS (browsers treat {@code http://localhost} as a secure
 * context, so no dev override is needed), {@code SameSite} is configurable via
 * {@code app.jwt.refresh-cookie-samesite} (default {@code Strict}) so the same
 * binary works under a same-site topology (Strict, the safest) and under a
 * cross-site topology served over TLS ({@code None} — note that the browser
 * additionally requires {@code Secure=true}, which is always on here, and HTTPS
 * end-to-end), and {@code Path=/api/auth} narrows the surface so the cookie is
 * only ever attached to the three auth endpoints.
 *
 * <p>Max-Age tracks {@code app.jwt.refresh-token-expiry-days}, matching the Redis TTL
 * in {@code RefreshTokenRepository} so the cookie expires alongside the server-side
 * record.
 */
@Component
public class RefreshTokenCookie {

    public static final String NAME = "refresh_token";

    private final Duration maxAge;
    private final String sameSite;

    public RefreshTokenCookie(@Value("${app.jwt.refresh-token-expiry-days:7}") int refreshTokenExpiryDays,
                              @Value("${app.jwt.refresh-cookie-samesite:Strict}") String sameSite) {
        this.maxAge = Duration.ofDays(refreshTokenExpiryDays);
        this.sameSite = sameSite;
    }

    public ResponseCookie issue(String tokenId) {
        return base(tokenId).maxAge(maxAge).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite(sameSite)
                .path("/api/auth");
    }
}
