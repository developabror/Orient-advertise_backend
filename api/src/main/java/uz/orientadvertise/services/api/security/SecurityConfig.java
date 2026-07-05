package uz.orientadvertise.services.api.security;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import uz.orientadvertise.services.domain.repository.DeviceRepository;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectProvider<ApiKeyAuthFilter> apiKeyAuthFilter;
    private final ObjectProvider<DeviceRepository> deviceRepositoryProvider;

    /**
     * When true, both the OpenAPI JSON and the Swagger UI require {@code ROLE_ADMIN}.
     * Defaulted to {@code true} so production locks down by default; the dev profile
     * (and tests) flip it to {@code false} via {@code application-dev.yml} /
     * {@code application-test.yml}.
     */
    @Value("${app.openapi.admin-only:true}")
    private boolean openApiAdminOnly;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                           ObjectProvider<ApiKeyAuthFilter> apiKeyAuthFilter,
                           ObjectProvider<DeviceRepository> deviceRepositoryProvider) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.apiKeyAuthFilter = apiKeyAuthFilter;
        this.deviceRepositoryProvider = deviceRepositoryProvider;
    }

    /**
     * Allowed origins for CORS, bound to {@code app.cors.allowed-origins} so deployment
     * can override per environment without rebuilding. Defaults live in the YAML files,
     * not here — see {@code application.yml} (FE dev origins) and
     * {@code application-prod.yml} (empty list, must be set explicitly).
     */
    @Value("${app.cors.allowed-origins:}")
    private List<String> corsAllowedOrigins;

    /**
     * Per-environment CORS for FE consumers.
     *
     * <p>Explicit allowed origins (not patterns) and {@code allowCredentials=true}. The
     * credentials flag is true because the refresh token rides as an HttpOnly cookie
     * (see {@link RefreshTokenCookie}) — the browser must be allowed to attach it on
     * cross-origin XHR/fetch from the FE, and the FE must send {@code credentials:
     * 'include'} on its auth calls. The CORS spec forbids combining
     * {@code allowCredentials=true} with a wildcard origin, which is why
     * {@code app.cors.allowed-origins} must list concrete origins in every environment.
     *
     * <p>{@code maxAge=3600} caches preflights for an hour so a clicky FE doesn't OPTIONS
     * every request individually.
     *
     * <p><b>WebSocket endpoints are NOT subject to this CORS.</b> {@code /ws/dashboard},
     * {@code /ws/devices/**}, and {@code /ws/admin/incidents} use the WebSocket protocol's
     * own {@code Origin} header check at handshake (handled by the handshake interceptor),
     * not the HTTP CORS preflight machinery. The {@code "/**"} mapping below is for HTTP
     * endpoints only — Spring's WebSocket support routes those handshakes through a
     * different path that bypasses the {@code CorsFilter}.
     */
    /** BCrypt for password hashing/verification (used by AuthService). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(corsAllowedOrigins == null ? List.of() : corsAllowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        // Exposed headers — only what the FE actually reads. Content-Disposition for
        // Excel/CSV downloads; X-RateLimit-* for the external API's rate-limit surface;
        // X-Export-Timeout-Seconds is set on synchronous export responses so the FE can
        // hint a timeout to the operator before falling back to the async job path.
        config.setExposedHeaders(List.of("Content-Disposition",
                "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset",
                "X-Export-Timeout-Seconds"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Customizer.withDefaults() makes Spring Security look up the bean named
        // "corsConfigurationSource" — avoids the by-type ambiguity caused by Spring MVC's
        // auto-registered mvcHandlerMappingIntrospector also implementing CorsConfigurationSource.
        // CSRF stays disabled. The only cookie this API sets is the refresh token
        // (HttpOnly, SameSite configurable (default Strict), Path=/api/auth) — under
        // SameSite=Strict a cross-site page cannot make the browser attach it to
        // /api/auth/refresh or /api/auth/logout, so those endpoints are not
        // CSRF-reachable. Under SameSite=None (cross-site TLS deployments) the FE
        // must opt-in by sending `credentials: 'include'`, and the CORS allow-list
        // pins which origins are permitted. No other state-changing endpoint reads
        // cookies; JWT travels in the Authorization header, which a cross-site page
        // cannot forge.
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // CORS preflight: never require auth. Spring Security's CorsFilter
                        // handles preflight responses for known endpoints, but listing
                        // OPTIONS as permitAll here makes it explicit and covers any path
                        // the dispatcher hasn't seen yet.
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        // Login/refresh/logout — AuthController is mapped at /api/auth.
                        .requestMatchers("/api/auth/**").permitAll()
                        // Liveness/readiness probe stays open; the rest of actuator
                        // (info, flyway → schema/version disclosure) is ADMIN-only.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/health").permitAll()
                        // First contact: a device has no token yet. Rate-limited in the
                        // controller (DeviceRegistrationRateLimiter) to blunt serial-guessing.
                        .requestMatchers("/api/devices/register").permitAll()
                        // Device-agent endpoints now require a valid X-Device-Token
                        // (DeviceTokenAuthFilter → ROLE_DEVICE). The token-vs-path-id binding
                        // (cross-device IDOR → 403) is enforced per-method via @PreAuthorize.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/devices/*/heartbeat").hasRole("DEVICE")
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/devices/*/sync").hasRole("DEVICE")
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/devices/*/time").hasRole("DEVICE")
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/devices/*/sync/confirm").hasRole("DEVICE")
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/devices/*/playlist").hasRole("DEVICE")
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/devices/*/actions/pending").hasRole("DEVICE")
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/devices/*/actions/*/confirm").hasRole("DEVICE")
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/devices/*/playback").hasRole("DEVICE")
                        // File download/presign are ADMIN/OPERATOR only — never reachable by
                        // ROLE_API_CLIENT (external partners) or unauthenticated callers.
                        .requestMatchers("/api/files/**").hasAnyRole("ADMIN", "OPERATOR")
                        // Swagger UI + OpenAPI JSON: locked to ADMIN by default. Dev/test
                        // profiles override `app.openapi.admin-only=false` to make the docs
                        // browseable without auth — never override this in production.
                        // Both the bare paths (when the proxy strips /api) and the
                        // /api/-prefixed paths (when the proxy forwards verbatim) are
                        // listed so swagger-ui works in either topology. The malformed
                        // `/api/swagger-ui.html/**` matcher that used to live above is
                        // folded in here — its dot-vs-slash mismatch meant requests for
                        // `/api/swagger-ui/index.html` slipped through to the catch-all
                        // and returned 401 even with admin-only=false.
                        .requestMatchers(
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/api/v3/api-docs/**", "/api/swagger-ui/**", "/api/swagger-ui.html"
                        ).access((authentication, ctx) -> {
                            if (!openApiAdminOnly) {
                                return new org.springframework.security.authorization.AuthorizationDecision(true);
                            }
                            var principal = authentication.get();
                            boolean granted = principal != null && principal.isAuthenticated()
                                    && principal.getAuthorities().stream()
                                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
                            return new org.springframework.security.authorization.AuthorizationDecision(granted);
                        })
                        .requestMatchers("/ws/admin/**").hasAnyRole("ADMIN", "OPERATOR")
                        // /ws/dashboard auth is enforced at the WebSocket handshake by
                        // DashboardHandshakeInterceptor (it can return distinct 401 vs 403
                        // codes, which the path matcher cannot). Listed explicitly above
                        // /ws/** so a future tightening here doesn't accidentally bypass
                        // the interceptor.
                        .requestMatchers("/ws/dashboard").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        // External integrations: API key only. JWT principals do NOT have
                        // ROLE_API_CLIENT, so a stolen JWT cannot reach these endpoints.
                        .requestMatchers("/api/external/**").hasRole("API_CLIENT")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType("application/json");
                            var correlationId = UUID.randomUUID().toString();
                            response.getWriter().write(
                                    "{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Authentication required\",\"correlationId\":\"%s\",\"timestamp\":\"%s\",\"fieldErrors\":null}"
                                            .formatted(correlationId, java.time.Instant.now().toString()));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType("application/json");
                            var correlationId = UUID.randomUUID().toString();
                            response.getWriter().write(
                                    "{\"status\":403,\"error\":\"Forbidden\",\"message\":\"Insufficient permissions\",\"correlationId\":\"%s\",\"timestamp\":\"%s\",\"fieldErrors\":null}"
                                            .formatted(correlationId, java.time.Instant.now().toString()));
                        })
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        // ApiKeyAuthFilter (when present) is registered BEFORE the JWT filter so the
        // X-API-Key path short-circuits before token extraction. ObjectProvider keeps
        // tests that don't import the filter or its dependencies (Redis, the
        // ApiKeyRepository) from failing to wire the security chain — a request with
        // neither header reaches the entry point and gets 401 either way.
        ApiKeyAuthFilter apiKey = apiKeyAuthFilter.getIfAvailable();
        if (apiKey != null) {
            http.addFilterBefore(apiKey, JwtAuthenticationFilter.class);
        }

        // DeviceTokenAuthFilter runs before the JWT filter so an X-Device-Token request
        // authenticates as ROLE_DEVICE. It is built inline from an ObjectProvider<DeviceRepository>
        // resolved HERE — at filter-chain bean-creation time, after every bean definition
        // (including the Spring Data JPA repository) is registered and type-resolvable.
        //
        // This replaces a former @ConditionalOnBean(DeviceRepository) guard on a separate
        // @Configuration: that condition is evaluated at configuration-parse time, when a Spring
        // Data repository's bean type is not yet known, so it false-negatived and SILENTLY dropped
        // the filter in the full app — every device-agent request then 401'd despite a valid token.
        // An ObjectProvider lookup is order-insensitive and fixes that. In @WebMvcTest slices (no
        // JPA layer) the provider stays empty, so device endpoints reach the entry point and 401 —
        // the intended slice behavior — and no Filter bean is exposed for web-slice auto-detection.
        DeviceRepository deviceRepository = deviceRepositoryProvider.getIfAvailable();
        if (deviceRepository != null) {
            http.addFilterBefore(new DeviceTokenAuthFilter(deviceRepository), JwtAuthenticationFilter.class);
            log.info("DeviceTokenAuthFilter registered — X-Device-Token auth is ACTIVE for the "
                    + "/api/devices/*/... agent endpoints.");
        } else {
            log.warn("DeviceTokenAuthFilter NOT registered (no DeviceRepository bean available) — "
                    + "/api/devices/*/... will 401 regardless of X-Device-Token. "
                    + "This is only expected in web-slice tests without the JPA layer.");
        }

        return http.build();
    }
}
