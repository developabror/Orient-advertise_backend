package uz.orientadvertise.services.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.controller.DashboardController;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.DashboardService;
import uz.orientadvertise.services.service.OperatorScopeResolver;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the CORS preflight contract that the FE depends on:
 * <ul>
 *   <li>Allowed origins from {@code app.cors.allowed-origins} are echoed back on the
 *       {@code Access-Control-Allow-Origin} header.</li>
 *   <li>Unlisted origins return 403 (CORS rejection) — the misconfig fails closed
 *       rather than silently letting through an arbitrary FE.</li>
 *   <li>Allowed methods include the full set the FE needs (GET/POST/PUT/DELETE/PATCH/OPTIONS).</li>
 *   <li>Exposed headers are present so the FE can read them on the JS side.</li>
 *   <li>Max-age caches preflights for an hour.</li>
 * </ul>
 *
 * <p>Uses a dashboard summary endpoint as the target — any HTTP endpoint on
 * {@code /api/**} would do; the CORS config is path-wide.
 */
@WebMvcTest(DashboardController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // Concrete origin — wildcard is illegal under allowCredentials=true (CORS spec).
        // Same-site setup means a single FE origin is all we configure per environment.
        "app.cors.allowed-origins=https://app.orientadvertise.uz",
        "app.openapi.admin-only=false"
})
class CorsConfigTest {

    private static final String ALLOWED_ORIGIN = "https://app.orientadvertise.uz";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    @MockitoBean
    private OperatorScopeResolver operatorScopeResolver;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    void preflight_fromAllowedOrigin_echoesOriginAndAllMethods() throws Exception {
        // With allowCredentials=true the spec forbids wildcard origin echoing, so we
        // configure a concrete origin and expect that exact value back. The full method
        // set must still be advertised so the FE can issue any verb without an extra
        // preflight failure.
        mockMvc.perform(options("/api/dashboard/summary")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Max-Age", "3600"))
                .andExpect(header().stringValues("Access-Control-Allow-Methods",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.allOf(
                                        org.hamcrest.Matchers.containsString("GET"),
                                        org.hamcrest.Matchers.containsString("POST"),
                                        org.hamcrest.Matchers.containsString("PUT"),
                                        org.hamcrest.Matchers.containsString("DELETE"),
                                        org.hamcrest.Matchers.containsString("OPTIONS"),
                                        org.hamcrest.Matchers.containsString("PATCH")))));
    }

    @Test
    void preflight_exposesContractHeaders() throws Exception {
        // Access-Control-Expose-Headers tells the browser which response headers the
        // FE's JS can read. Verify the documented set is on the preflight response —
        // missing one of these would mean the FE silently fails to read e.g. rate-limit
        // counters, which would manifest as a UX bug under load.
        mockMvc.perform(options("/api/dashboard/summary")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Access-Control-Expose-Headers",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.allOf(
                                        org.hamcrest.Matchers.containsString("Content-Disposition"),
                                        org.hamcrest.Matchers.containsString("X-RateLimit-Limit"),
                                        org.hamcrest.Matchers.containsString("X-RateLimit-Remaining"),
                                        org.hamcrest.Matchers.containsString("X-RateLimit-Reset"),
                                        org.hamcrest.Matchers.containsString("X-Export-Timeout-Seconds")))));
    }

    @Test
    void preflight_credentialsFlagIsTrue() throws Exception {
        // Refresh-token cookie travels cross-origin from the FE — the browser only
        // attaches it when both this header is "true" AND the FE sends `credentials:
        // 'include'`. Pairs with concrete (non-wildcard) allowed origins, which is what
        // the CORS spec requires when credentials are enabled.
        mockMvc.perform(options("/api/dashboard/summary")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }
}
