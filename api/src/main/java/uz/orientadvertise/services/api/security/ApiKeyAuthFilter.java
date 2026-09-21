package uz.orientadvertise.services.api.security;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import uz.orientadvertise.services.service.ApiKeyAuthenticationService;
import uz.orientadvertise.services.service.ApiKeyFailureRateLimiter;
import uz.orientadvertise.services.service.ApiKeyRateLimiter;

/**
 * Authenticates external integrations via {@code X-API-Key}, applies the per-key
 * rate limit, and writes {@code X-RateLimit-*} headers on every response.
 *
 * <p>Behavior matrix:
 * <ul>
 *   <li><b>No {@code X-API-Key} header</b> — pass through. The JWT filter (or anonymous
 *       access) handles the request as before.</li>
 *   <li><b>Header present, key invalid or revoked</b> — write {@code 401} immediately.
 *       Revocation works because {@link ApiKeyAuthenticationService#authenticate} hits
 *       the DB on every request — no in-memory cache widens the revocation window.</li>
 *   <li><b>Key valid, under limit</b> — set {@code SecurityContext} with role
 *       {@code API_CLIENT}, write rate-limit headers, continue.</li>
 *   <li><b>Key valid, over limit</b> — write {@code 429} with rate-limit headers so
 *       the client knows when to retry.</li>
 * </ul>
 *
 * <p>Internal IDs (user ids, internal device ids) are never written to logs or to the
 * authentication name — the principal is the public {@code keyPrefix}, never the
 * numeric DB id.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    public static final String HEADER_RESET = "X-RateLimit-Reset";

    public static final String ROLE = "ROLE_API_CLIENT";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private final ApiKeyAuthenticationService authService;
    private final ApiKeyRateLimiter rateLimiter;
    private final ApiKeyFailureRateLimiter failureRateLimiter;

    public ApiKeyAuthFilter(ApiKeyAuthenticationService authService,
                             ApiKeyRateLimiter rateLimiter,
                             ApiKeyFailureRateLimiter failureRateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.failureRateLimiter = failureRateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        var keyOpt = authService.authenticate(key);
        if (keyOpt.isEmpty()) {
            // Throttle invalid-key attempts per source IP so the 401 path can't be used to
            // brute-force the key space (the per-key limiter below never runs for a bad key).
            if (!failureRateLimiter.allowFailure(ClientIp.of(request))) {
                writeError(response, HttpStatus.TOO_MANY_REQUESTS,
                        "Too many failed API key attempts from this source");
                return;
            }
            // Either unknown key or revoked. Both surface as 401, never 403, so the
            // client doesn't get to disambiguate "valid but no permission" from
            // "secret is dead".
            log.info("Rejected API key request [prefix={}]",
                    ApiKeyAuthenticationService.prefixOf(key));
            writeError(response, HttpStatus.UNAUTHORIZED, "Invalid or revoked API key");
            return;
        }
        var apiKey = keyOpt.get();

        var decision = rateLimiter.tryAcquire(apiKey.getId());
        // Rate-limit headers go on EVERY response — the success path AND the 429.
        // Clients build retry/backoff from these regardless of outcome.
        response.setHeader(HEADER_LIMIT, String.valueOf(decision.limit()));
        response.setHeader(HEADER_REMAINING, String.valueOf(decision.remaining()));
        response.setHeader(HEADER_RESET, String.valueOf(decision.resetEpochSeconds()));

        if (!decision.allowed()) {
            writeError(response, HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded: " + decision.limit() + " req/hour");
            return;
        }

        // Principal is the public prefix, never the internal id. Anything that calls
        // SecurityContextHolder.getAuthentication().getName() will see the prefix only.
        var auth = new UsernamePasswordAuthenticationToken(
                apiKey.getKeyPrefix(), null, List.of(new SimpleGrantedAuthority(ROLE)));
        auth.setDetails(apiKey.getId());
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }


    private static void writeError(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        var correlationId = UUID.randomUUID().toString();
        response.getWriter().write(
                "{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\",\"correlationId\":\"%s\",\"timestamp\":\"%s\",\"fieldErrors\":null}"
                        .formatted(status.value(), status.getReasonPhrase(), message,
                                correlationId, Instant.now().toString()));
    }
}
