package uz.orientadvertise.services.api.ws;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.service.OperatorScopeResolver;
import uz.orientadvertise.services.service.OperatorScopeResolver.ScopedProjects;

/**
 * Authenticates the {@code /ws/dashboard} handshake from the JWT carried in the
 * {@code Authorization: Bearer ...} header (or, fallback, the {@code access_token} query
 * param for browser EventSource-style clients that can't set headers).
 *
 * <p>Rejects with {@code 401} when no token is present or the token is invalid/expired.
 * Rejects with {@code 403} when the role is anything other than {@code ADMIN} or
 * {@code OPERATOR} — VIEWER/ADVERTISER cannot subscribe to the dashboard live feed.
 *
 * <p>On success, stores the username and roles on the WebSocket session attributes so
 * the handler can audit the connection without re-validating the token.
 */
@Component
public class DashboardHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_ROLES = "roles";
    /** Per-session operator project scope: {@code null} for ADMIN (unrestricted), a {@code Set<Long>} (possibly empty) for OPERATOR. */
    public static final String ATTR_PROJECT_IDS = "projectIds";

    private static final Logger log = LoggerFactory.getLogger(DashboardHandshakeInterceptor.class);
    private static final Set<String> ALLOWED_ROLES = Set.of("ROLE_ADMIN", "ROLE_OPERATOR");

    private final TokenValidator tokenValidator;
    private final OperatorScopeResolver operatorScopeResolver;

    public DashboardHandshakeInterceptor(TokenValidator tokenValidator,
                                         OperatorScopeResolver operatorScopeResolver) {
        this.tokenValidator = tokenValidator;
        this.operatorScopeResolver = operatorScopeResolver;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                     WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            log.debug("Dashboard handshake rejected: missing token");
            return false;
        }

        try {
            tokenValidator.validateOrThrow(token);
        } catch (AuthenticationException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            log.debug("Dashboard handshake rejected: invalid token ({})", e.getMessage());
            return false;
        }

        List<String> roles = tokenValidator.extractRoles(token);
        boolean allowed = roles != null && roles.stream().anyMatch(ALLOWED_ROLES::contains);
        if (!allowed) {
            // 403 specifically for "valid token, wrong role" — the spec calls this out.
            response.setStatusCode(HttpStatus.FORBIDDEN);
            log.info("Dashboard handshake rejected: role not allowed [roles={}]", roles);
            return false;
        }

        String username = tokenValidator.extractUsername(token);
        attributes.put(ATTR_USERNAME, username);
        attributes.put(ATTR_ROLES, roles);
        // Capture the per-session project scope NOW (the handshake has no SecurityContext, so
        // use the context-free resolver). Stored once; every snapshot/delta filters against it.
        // The lookup goes interceptor → OperatorScopeResolver → repo (never interceptor → repo).
        ScopedProjects scope = operatorScopeResolver.resolveForUsername(username);
        attributes.put(ATTR_PROJECT_IDS, scope.restricted() ? Set.copyOf(scope.projectIds()) : null);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                 WebSocketHandler wsHandler, Exception exception) {
        // No-op — connection state is owned by the handler.
    }

    private static String extractToken(ServerHttpRequest request) {
        // Header is the preferred carrier (works from any non-browser client).
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        // Browser fallback: WebSocket constructor in JS doesn't accept headers, so the
        // dashboard frontend appends ?access_token=... to the handshake URL.
        if (request instanceof ServletServerHttpRequest servletReq) {
            String token = servletReq.getServletRequest().getParameter("access_token");
            if (token != null && !token.isBlank()) {
                return token;
            }
        }
        return null;
    }
}
