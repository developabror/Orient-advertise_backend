package uz.orientadvertise.services.api.ws;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DeviceWebSocketHandler handler;
    private final AdminIncidentWebSocketHandler adminHandler;
    private final DashboardWebSocketHandler dashboardHandler;
    private final DashboardHandshakeInterceptor dashboardInterceptor;
    private final AdminIncidentHandshakeInterceptor adminIncidentInterceptor;
    private final DeviceTokenHandshakeInterceptor deviceInterceptor;

    /**
     * Same allow-list as HTTP CORS ({@code app.cors.allowed-origins}). Replaces the former
     * {@code setAllowedOriginPatterns("*")} so a browser page on an unlisted origin can't
     * open any of these sockets. Non-browser device agents send no {@code Origin} header
     * and are unaffected. Empty in prod by default → must be set per deployment.
     */
    @Value("${app.cors.allowed-origins:}")
    private List<String> allowedOrigins;

    public WebSocketConfig(DeviceWebSocketHandler handler,
                            AdminIncidentWebSocketHandler adminHandler,
                            DashboardWebSocketHandler dashboardHandler,
                            DashboardHandshakeInterceptor dashboardInterceptor,
                            AdminIncidentHandshakeInterceptor adminIncidentInterceptor,
                            DeviceTokenHandshakeInterceptor deviceInterceptor) {
        this.handler = handler;
        this.adminHandler = adminHandler;
        this.dashboardHandler = dashboardHandler;
        this.dashboardInterceptor = dashboardInterceptor;
        this.adminIncidentInterceptor = adminIncidentInterceptor;
        this.deviceInterceptor = deviceInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = allowedOrigins == null ? new String[0] : allowedOrigins.toArray(new String[0]);

        // Path: /ws/devices/{id} — the trailing path segment is the deviceId.
        // The handshake interceptor validates X-Device-Token (header or ?device_token=)
        // and binds it to the path id (cross-device → 403; missing/invalid → 401).
        registry.addHandler(handler, "/ws/devices/*")
                .addInterceptors(deviceInterceptor)
                .setAllowedOrigins(origins);

        // Admin/Operator live incident feed. Auth + role enforced by Spring Security
        // on the /ws/admin/** path matcher; the handler itself just maintains sessions
        // and broadcasts batched payloads from BatchedIncidentBroadcaster.
        registry.addHandler(adminHandler, "/ws/admin/incidents")
                .addInterceptors(adminIncidentInterceptor)
                .setAllowedOrigins(origins);

        // Dashboard live feed (FE-05 / FE-14 / FE-29). Three event types, broadcast-only.
        // Role enforcement is at the handshake interceptor rather than via SecurityConfig
        // path matchers — the spec calls for distinct 401 (no token) vs 403 (wrong role)
        // responses, which the interceptor controls precisely. The path is also
        // permitAll-ed in SecurityConfig so the interceptor is the single source of auth.
        registry.addHandler(dashboardHandler, "/ws/dashboard")
                .addInterceptors(dashboardInterceptor)
                .setAllowedOrigins(origins);
    }
}
