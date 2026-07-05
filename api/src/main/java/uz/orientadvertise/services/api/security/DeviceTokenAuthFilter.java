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
import uz.orientadvertise.services.domain.repository.DeviceRepository;

/**
 * Authenticates device agents via the {@code X-Device-Token} header (the {@code dtk_...}
 * token minted at registration). Mirrors {@link ApiKeyAuthFilter}:
 * <ul>
 *   <li><b>No header</b> — pass through; the JWT filter / anonymous handling decides.</li>
 *   <li><b>Header present, token unknown or device soft-deleted</b> — write {@code 401}
 *       immediately. The lookup hits the DB every request, so a deleted device's token
 *       stops working with no cache window.</li>
 *   <li><b>Token valid</b> — authenticate with principal = the device's <em>id</em>
 *       (a {@code Long}) and authority {@code ROLE_DEVICE}.</li>
 * </ul>
 *
 * <p>The principal is the numeric device id on purpose: each device-agent endpoint is
 * guarded by {@code @PreAuthorize("hasRole('DEVICE') and #id == authentication.principal")}
 * so a valid token for device A cannot act on device B's path id (cross-device IDOR → 403).
 */
public class DeviceTokenAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Device-Token";
    public static final String ROLE = "ROLE_DEVICE";

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenAuthFilter.class);

    private final DeviceRepository deviceRepository;

    public DeviceTokenAuthFilter(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            // A device-agent endpoint is /api/devices/{id}/{action}; the open register
            // endpoint and admin CRUD (/api/devices, /api/devices/{id}) have no action
            // sub-path. Logging the absent header only for agent paths surfaces a
            // stripped-in-transit or never-sent X-Device-Token (the usual cause of a
            // device 401) without a line per JWT/anonymous request. The request still
            // falls through and 401s at the entry point as "Authentication required" —
            // which, unlike the rejection below, otherwise leaves no trace it was a device.
            if (isDeviceAgentPath(request)) {
                log.info("Device-agent request to {} carried no {} header — falling through to "
                        + "JWT/anonymous (will 401 'Authentication required'). If the agent sent the "
                        + "token, a proxy in front of the app is likely stripping it.",
                        request.getRequestURI(), HEADER);
            }
            filterChain.doFilter(request, response);
            return;
        }

        var deviceOpt = deviceRepository.findByDeviceTokenAndDeletedAtIsNull(token);
        if (deviceOpt.isEmpty()) {
            // Unknown token or the device was soft-deleted. 401 with no detail so a caller
            // can't probe which tokens exist.
            log.info("Rejected device-token request — unknown or revoked token");
            writeError(response, HttpStatus.UNAUTHORIZED, "Invalid or revoked device token");
            return;
        }

        var device = deviceOpt.get();
        var auth = new UsernamePasswordAuthenticationToken(
                device.getId(), null, List.of(new SimpleGrantedAuthority(ROLE)));
        SecurityContextHolder.getContext().setAuthentication(auth);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * True for the token-authenticated device-agent endpoints — {@code /api/devices/{id}/{action}}
     * (heartbeat, sync, playlist, actions, playback). The open {@code /api/devices/register} and
     * the JWT-guarded admin CRUD ({@code /api/devices}, {@code /api/devices/{id}}) have no action
     * segment after the id, so they don't trip the missing-token log.
     */
    private static boolean isDeviceAgentPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String prefix = "/api/devices/";
        // An action segment exists iff there is a '/' after the {id} that follows the prefix.
        return uri != null && uri.startsWith(prefix) && uri.indexOf('/', prefix.length()) >= 0;
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
