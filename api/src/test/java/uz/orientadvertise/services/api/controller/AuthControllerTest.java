package uz.orientadvertise.services.api.controller;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.advice.GlobalExceptionHandler;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.RefreshTokenCookie;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.AuthToken;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.AuthService;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, RefreshTokenCookie.class})
class AuthControllerTest {

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
    void login_success_returnsAccessTokenInBodyAndRefreshCookie() throws Exception {
        when(authService.login(any())).thenReturn(new AuthToken("access-jwt", "refresh-id"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                // Refresh token MUST NOT leak into the JSON body — it now travels only via cookie.
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        startsWith("refresh_token=refresh-id"),
                        containsString("HttpOnly"),
                        containsString("Secure"),
                        containsString("SameSite=Strict"),
                        containsString("Path=/api/auth"),
                        containsString("Max-Age="))));
    }

    @Test
    void login_invalidCredentials_returns401() throws Exception {
        when(authService.login(any())).thenThrow(new AuthenticationException("Invalid credentials"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void login_rateLimitKey_isTheResolvedRemoteAddress_notAClientSuppliedForwardedFor() throws Exception {
        // AUTH-04: Tomcat's RemoteIpValve resolves forwarding (only from trusted proxies). The
        // controller must not read X-Forwarded-For itself, or any client picks its own bucket.
        when(authService.login(any())).thenReturn(new AuthToken("access-jwt", "refresh-id"));

        mockMvc.perform(post("/api/auth/login")
                        .with(request -> { request.setRemoteAddr("203.0.113.9"); return request; })
                        .header("X-Forwarded-For", "9.9.9.9")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk());

        verify(loginRateLimiter).checkAllowed("203.0.113.9", "alice");
    }

    @Test
    void refresh_success_rotatesCookieAndReturnsNewAccessToken() throws Exception {
        when(authService.refresh("old-refresh")).thenReturn(new AuthToken("new-access", "new-refresh"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", "old-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        startsWith("refresh_token=new-refresh"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Strict"),
                        containsString("Path=/api/auth"))));
    }

    @Test
    void refresh_missingCookie_returns401WithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Missing refresh cookie"));

        verifyNoInteractions(authService);
    }

    @Test
    void refresh_reuseAttack_returns401() throws Exception {
        when(authService.refresh("reused-token"))
                .thenThrow(new AuthenticationException("Invalid refresh token — possible reuse attack"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", "reused-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid refresh token — possible reuse attack"));
    }

    @Test
    void logout_withCookie_invalidatesFamilyAndClearsCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie("refresh_token", "some-token")))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        startsWith("refresh_token=;"),
                        containsString("Max-Age=0"),
                        containsString("Path=/api/auth"),
                        containsString("HttpOnly"))));

        verify(authService).logout("some-token");
    }

    @Test
    void logout_missingCookie_returns204AndClearsCookieWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        startsWith("refresh_token=;"),
                        containsString("Max-Age=0"))))
                // The service must not be told to invalidate "no token" — that would mis-log
                // and could spam audit. Cookie clearing is purely client-side state.
                .andExpect(header().string(HttpHeaders.SET_COOKIE, not(containsString("some-token"))));

        verifyNoInteractions(authService);
    }
}
