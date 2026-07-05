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
 * Handshake interceptor for {@code /ws/admin/incidents} — a structural mirror of
 * {@link DashboardHandshakeInterceptor}. The channel's auth is enforced by Spring Security's
 * {@code /ws/admin/**} matcher; this interceptor additionally captures the per-session operator
 * project scope ({@link #ATTR_PROJECT_IDS}) so the batched {@code CRITICAL_INCIDENTS} feed can be
 * filtered per item per session. ADMIN sessions store {@code null} (unrestricted); OPERATOR
 * sessions store their (possibly empty) project set.
 *
 * <p>The project lookup goes interceptor → {@link OperatorScopeResolver} → repository, never
 * interceptor → repository (keeps the api.ws layer off the domain.repository layer).
 */
@Component
public class AdminIncidentHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_USERNAME = "username";
    public static final String ATTR_ROLES = "roles";
    public static final String ATTR_PROJECT_IDS = "projectIds";

    private static final Logger log = LoggerFactory.getLogger(AdminIncidentHandshakeInterceptor.class);
    private static final Set<String> ALLOWED_ROLES = Set.of("ROLE_ADMIN", "ROLE_OPERATOR");

    private final TokenValidator tokenValidator;
    private final OperatorScopeResolver operatorScopeResolver;

    public AdminIncidentHandshakeInterceptor(TokenValidator tokenValidator,
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
            return false;
        }
        try {
            tokenValidator.validateOrThrow(token);
        } catch (AuthenticationException e) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        List<String> roles = tokenValidator.extractRoles(token);
        boolean allowed = roles != null && roles.stream().anyMatch(ALLOWED_ROLES::contains);
        if (!allowed) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            log.info("Admin-incident handshake rejected: role not allowed [roles={}]", roles);
            return false;
        }

        String username = tokenValidator.extractUsername(token);
        attributes.put(ATTR_USERNAME, username);
        attributes.put(ATTR_ROLES, roles);
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
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        if (request instanceof ServletServerHttpRequest servletReq) {
            String token = servletReq.getServletRequest().getParameter("access_token");
            if (token != null && !token.isBlank()) {
                return token;
            }
        }
        return null;
    }
}
