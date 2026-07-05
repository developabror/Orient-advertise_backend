package uz.orientadvertise.services.api.ws;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import uz.orientadvertise.services.api.security.DeviceTokenAuthFilter;
import uz.orientadvertise.services.domain.repository.DeviceRepository;

/**
 * Authenticates the device WebSocket handshake on {@code /ws/devices/{id}}. Sibling of
 * {@link DashboardHandshakeInterceptor}.
 *
 * <p>Reads the {@code dtk_...} token from the {@code X-Device-Token} header (preferred) or
 * the {@code ?device_token=} query param (browsers can't set WS headers), validates it
 * against the persisted, non-soft-deleted device, and requires the token's device id to
 * match the {@code {id}} in the path:
 * <ul>
 *   <li>missing/blank token, or unknown/revoked → <b>401</b>;</li>
 *   <li>token valid but its device ≠ the path id (cross-device hijack) → <b>403</b>;</li>
 *   <li>otherwise the device id is stored in the session attributes and the handshake proceeds.</li>
 * </ul>
 */
@Component
public class DeviceTokenHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_DEVICE_ID = "deviceId";

    private static final Logger log = LoggerFactory.getLogger(DeviceTokenHandshakeInterceptor.class);

    private final DeviceRepository deviceRepository;

    public DeviceTokenHandshakeInterceptor(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            log.debug("Device WS handshake rejected: missing token");
            return false;
        }
        Long pathDeviceId = parsePathDeviceId(request);
        if (pathDeviceId == null) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            log.debug("Device WS handshake rejected: no device id in path");
            return false;
        }
        var deviceOpt = deviceRepository.findByDeviceTokenAndDeletedAtIsNull(token);
        if (deviceOpt.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            log.info("Device WS handshake rejected: unknown or revoked token");
            return false;
        }
        var device = deviceOpt.get();
        if (!pathDeviceId.equals(device.getId())) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            log.warn("Device WS handshake rejected: token device {} != path device {}",
                    device.getId(), pathDeviceId);
            return false;
        }
        attributes.put(ATTR_DEVICE_ID, device.getId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private static String extractToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(DeviceTokenAuthFilter.HEADER);
        if (header != null && !header.isBlank()) {
            return header;
        }
        if (request instanceof ServletServerHttpRequest servletReq) {
            String q = servletReq.getServletRequest().getParameter("device_token");
            if (q != null && !q.isBlank()) {
                return q;
            }
        }
        return null;
    }

    private static Long parsePathDeviceId(ServerHttpRequest request) {
        var path = request.getURI().getPath();
        if (path == null) {
            return null;
        }
        int idx = path.lastIndexOf('/');
        if (idx < 0 || idx == path.length() - 1) {
            return null;
        }
        try {
            return Long.parseLong(path.substring(idx + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
