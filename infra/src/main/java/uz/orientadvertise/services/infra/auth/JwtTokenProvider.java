package uz.orientadvertise.services.infra.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.auth.TokenValidator;

@Component
@EnableConfigurationProperties(JwtProperties.class)
public class JwtTokenProvider implements TokenValidator {

    private final SecretKey key;
    private final JwtProperties properties;

    /** HS256 needs a key of at least 256 bits (32 bytes). */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        String secret = properties.getSecret();
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            // Fail fast at startup rather than boot with a missing/weak key. Set JWT_SECRET
            // (≥32 bytes) in the environment; dev/test profiles supply their own.
            throw new IllegalStateException(
                    "app.jwt.secret must be set and at least " + MIN_SECRET_BYTES + " bytes long");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String createAccessToken(String username, Role role) {
        var now = Instant.now();
        var expiry = now.plus(properties.getAccessTokenExpiryMinutes(), ChronoUnit.MINUTES);

        return Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim("type", "access")
                .claim("roles", List.of("ROLE_" + role.name()))
                .signWith(key)
                .compact();
    }

    @Override
    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> extractRoles(String token) {
        var claims = parseClaims(token);
        var roles = claims.get("roles", List.class);
        if (roles == null) {
            return List.of();
        }
        return (List<String>) roles;
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void validateOrThrow(String token) {
        try {
            parseClaims(token);
        } catch (ExpiredJwtException e) {
            throw new AuthenticationException("Access token expired");
        } catch (JwtException e) {
            throw new AuthenticationException("Invalid access token");
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
