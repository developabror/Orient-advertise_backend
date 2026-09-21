package uz.orientadvertise.services.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.advice.GlobalExceptionHandler;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.RateLimitExceededException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.PasswordResetRateLimiter;
import uz.orientadvertise.services.service.PasswordService;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PasswordResetController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class PasswordResetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PasswordService passwordService;

    @MockitoBean
    private PasswordResetRateLimiter rateLimiter;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    // --- POST /api/auth/forgot-password -------------------------------------

    @Test
    void forgot_knownEmail_returns202() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"known@example.com"}"""))
                .andExpect(status().isAccepted());
        verify(passwordService).requestReset(anyString(), anyString());
    }

    @Test
    void forgot_rateLimitIp_isTheResolvedRemoteAddress_notAClientSuppliedForwardedFor() throws Exception {
        // AUTH-04: the controller must not read X-Forwarded-For itself (RemoteIpValve does, and only
        // from trusted proxies).
        mockMvc.perform(post("/api/auth/forgot-password")
                        .with(request -> { request.setRemoteAddr("203.0.113.9"); return request; })
                        .header("X-Forwarded-For", "9.9.9.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"known@example.com"}"""))
                .andExpect(status().isAccepted());
        verify(passwordService).requestReset("known@example.com", "203.0.113.9");
    }

    @Test
    void forgot_unknownEmail_stillReturns202_noEnumeration() throws Exception {
        // The service returns void either way; the controller must answer 202 identically.
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.com"}"""))
                .andExpect(status().isAccepted());
    }

    @Test
    void forgot_malformedEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forgot_rateLimited_returns429() throws Exception {
        // The forgot rate-limit lives inside requestReset (per spec §5.1); prove the controller
        // surfaces a 429 when it trips and GlobalExceptionHandler maps it on this route.
        doThrow(new RateLimitExceededException("Too many password-reset requests; please try again later."))
                .when(passwordService).requestReset(anyString(), anyString());

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"a@b.com"}"""))
                .andExpect(status().isTooManyRequests());
    }

    // --- POST /api/auth/reset-password --------------------------------------

    @Test
    void reset_validToken_returns204() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"raw-token","newPassword":"newpass12","confirmPassword":"newpass12"}"""))
                .andExpect(status().isNoContent());
        verify(passwordService).resetPassword("raw-token", "newpass12", "newpass12");
    }

    @Test
    void reset_badToken_returns400() throws Exception {
        doThrow(new IllegalArgumentException("This reset link is invalid or has expired. Request a new one."))
                .when(passwordService).resetPassword(anyString(), anyString(), anyString());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"bad","newPassword":"newpass12","confirmPassword":"newpass12"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reset_rateLimited_returns429() throws Exception {
        doThrow(new RateLimitExceededException("Too many password-reset attempts; please try again later."))
                .when(rateLimiter).checkResetAllowed(anyString());

        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"raw","newPassword":"newpass12","confirmPassword":"newpass12"}"""))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void reset_shortPassword_returns400_dtoValidation() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"raw","newPassword":"short","confirmPassword":"short"}"""))
                .andExpect(status().isBadRequest());
    }

    // --- GET /api/auth/reset-password?token= --------------------------------

    @Test
    void validate_returnsValidFlag() throws Exception {
        when(passwordService.isResetTokenValid("live-token")).thenReturn(true);

        mockMvc.perform(get("/api/auth/reset-password").param("token", "live-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void validate_rateLimited_returns429() throws Exception {
        doThrow(new RateLimitExceededException("Too many password-reset attempts; please try again later."))
                .when(rateLimiter).checkResetAllowed(anyString());

        mockMvc.perform(get("/api/auth/reset-password").param("token", "x"))
                .andExpect(status().isTooManyRequests());
    }
}
