package uz.orientadvertise.services.api.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.advice.GlobalExceptionHandler;
import uz.orientadvertise.services.api.controller.AuthController;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.AuthToken;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.AuthService;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cross-site (TLS) topologies require {@code SameSite=None}; the value is bound
 * to {@code app.jwt.refresh-cookie-samesite}. This slice flips the property and
 * verifies the issued and cleared cookies pick up the override. The
 * complementary "default = Strict" assertion lives in
 * {@code AuthControllerTest#login_success_returnsAccessTokenInBodyAndRefreshCookie}.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, RefreshTokenCookie.class})
@TestPropertySource(properties = {
        "app.jwt.refresh-cookie-samesite=None",
        "app.openapi.admin-only=false"
})
class RefreshTokenCookieSameSiteTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private uz.orientadvertise.services.service.LoginRateLimiter loginRateLimiter;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    void login_emitsSameSiteNoneCookie_whenPropertyOverridesDefault() throws Exception {
        when(authService.login(any())).thenReturn(new AuthToken("access-jwt", "refresh-id"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("SameSite=None"),
                        containsString("Secure"),
                        containsString("HttpOnly"),
                        containsString("Path=/api/auth"),
                        // Strict must NOT slip through when the override is set, otherwise
                        // the cross-site browser would silently drop the cookie.
                        not(containsString("SameSite=Strict")))));
    }

    @Test
    void logout_emitsSameSiteNoneClearCookie_whenPropertyOverridesDefault() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie("refresh_token", "doesnt-matter")))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("SameSite=None"),
                        containsString("Max-Age=0"),
                        containsString("Path=/api/auth"))));
    }
}
