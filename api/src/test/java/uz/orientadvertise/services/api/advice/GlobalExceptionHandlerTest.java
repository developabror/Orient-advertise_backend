package uz.orientadvertise.services.api.advice;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.advice.GlobalExceptionHandler;
import uz.orientadvertise.services.api.controller.AuthController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.RefreshTokenCookie;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.AuthenticationException;
import uz.orientadvertise.services.common.exception.IllegalConfigurationException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.auth.AuthToken;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.notification.TelegramNotifier;
import uz.orientadvertise.services.service.AuthService;
import uz.orientadvertise.services.service.exception.AssignmentTimeOverlapException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class,
        RefreshTokenCookie.class, GlobalExceptionHandlerTest.SyncExecutorConfig.class})
class GlobalExceptionHandlerTest {

    /**
     * Provides a synchronous {@code auditExecutor} so the test thread observes
     * {@code broadcastMarkdown} before the assertion fires. The production executor
     * is async + drop-on-overflow; we'd otherwise need {@code Mockito.timeout(...)}
     * polling, which adds wall-clock noise to the suite.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class SyncExecutorConfig {
        @org.springframework.context.annotation.Bean(name = "auditExecutor")
        public java.util.concurrent.Executor auditExecutor() {
            return Runnable::run;
        }
    }

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

    @MockitoBean
    private TelegramNotifier telegramNotifier;

    @Test
    void validationError_listsAllFailedFields() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void validationError_reportsUsernameRequired() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"validpass\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("username"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Username is required"));
    }

    @Test
    void validationError_neverEchoesARejectedPassword() throws Exception {
        // AUTH-06: the 400 body is written to audit_log too; a too-short password must not be echoed.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        // "Zq!" cannot occur in the random correlation id (lowercase hex) or timestamp.
                        .content("{\"username\":\"alice\",\"password\":\"Zq!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("$.fieldErrors[0].rejectedValue").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Zq!"))));
    }

    @Test
    void safeRejectedValue_dropsOnlyCredentialFields() {
        assertEquals("ab", GlobalExceptionHandler.safeRejectedValue("username", "ab"));
        assertEquals("x", GlobalExceptionHandler.safeRejectedValue("items[0].name", "x"));
        assertNull(GlobalExceptionHandler.safeRejectedValue("password", "abc"));
        assertNull(GlobalExceptionHandler.safeRejectedValue("create.request.newPassword", "abc"));
        assertNull(GlobalExceptionHandler.safeRejectedValue("users[2].password", "abc"));
        assertNull(GlobalExceptionHandler.safeRejectedValue("passwords[0]", "abc"));
        assertNull(GlobalExceptionHandler.safeRejectedValue("props[password]", "abc"), "a sensitive map key");
        assertNull(GlobalExceptionHandler.safeRejectedValue("create.passwords[0].<list element>", "abc"));
    }

    @Test
    void safePath_masksSensitiveQueryParameters_forTheTelegramAlert() {
        var req = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/auth/reset-password");
        req.setQueryString("token=live-reset-token&lang=ru");

        var path = GlobalExceptionHandler.safePath(req);

        assertEquals("/api/auth/reset-password?token=***REDACTED***&lang=ru", path);
    }

    @Test
    void authenticationError_returns401_withCorrelationId() throws Exception {
        when(authService.login(any())).thenThrow(new AuthenticationException("Invalid credentials"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    void notFound_returns404_withCorrelationId() throws Exception {
        mockMvc.perform(get("/api/auth/nonexistent-path"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void uniformResponse_alwaysHasRequiredFields() throws Exception {
        when(authService.login(any())).thenReturn(new AuthToken("a", "b"));

        // Even a 400 has all the uniform fields
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(jsonPath("$.status").isNumber())
                .andExpect(jsonPath("$.error").isString())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.correlationId").isString())
                .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test
    void noStackTrace_inErrorMessage_byDefault() throws Exception {
        when(authService.login(any())).thenThrow(new RuntimeException("internal error with sensitive data"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.startsWith("An unexpected error occurred. Reference:")));
    }

    // --- Telegram forwarding ---

    @Test
    void unhandled500_forwardsToTelegram_withFullContext() throws Exception {
        when(authService.login(any()))
                .thenThrow(new RuntimeException("simulated db connection lost"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isInternalServerError());

        verify(telegramNotifier).broadcastMarkdown(argThat(payload ->
                ((String) payload).contains("HTTP 500")
                && ((String) payload).contains("method      : POST")
                && ((String) payload).contains("path        : /api/auth/login")
                && ((String) payload).contains("user        : anonymous")
                && ((String) payload).contains("correlation : ")
                && ((String) payload).contains("RuntimeException")
                && ((String) payload).contains("simulated db connection lost")
                && ((String) payload).contains("at ")),
                org.mockito.ArgumentMatchers.eq(
                        uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity.ERROR));
    }

    @Test
    void unhandled500_includesAuthenticatedUserInPayload() throws Exception {
        when(authService.login(any()))
                .thenThrow(new RuntimeException("kaboom"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("alice").roles("ADMIN")))
                .andExpect(status().isInternalServerError());

        verify(telegramNotifier).broadcastMarkdown(
                argThat(p -> ((String) p).contains("user        : alice")),
                org.mockito.ArgumentMatchers.eq(
                        uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity.ERROR));
    }

    @Test
    void validation400_doesNotForwardToTelegram() throws Exception {
        // The spec is explicit: only true server errors. Validation failures are
        // expected and would drown operators in noise.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(telegramNotifier, never()).broadcastMarkdown(any(), any());
        verify(telegramNotifier, never()).broadcastMarkdown(any());
    }

    @Test
    void notFound404_doesNotForwardToTelegram() throws Exception {
        mockMvc.perform(get("/api/auth/nonexistent-path"))
                .andExpect(status().isNotFound());

        verify(telegramNotifier, never()).broadcastMarkdown(any(), any());
        verify(telegramNotifier, never()).broadcastMarkdown(any());
    }

    @Test
    void unauthorized401_doesNotForwardToTelegram() throws Exception {
        when(authService.login(any())).thenThrow(new AuthenticationException("Invalid credentials"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());

        verify(telegramNotifier, never()).broadcastMarkdown(any(), any());
        verify(telegramNotifier, never()).broadcastMarkdown(any());
    }

    @Test
    void telegramFailure_doesNotBreakHttpResponse() throws Exception {
        // Telegram broadcast throwing must never propagate back into the response —
        // the user sees a normal 500, the operator misses one alert. Better than
        // turning a single failed request into a ResponseEntityException-double-fault.
        when(authService.login(any())).thenThrow(new RuntimeException("kaboom"));
        org.mockito.Mockito.doThrow(new RuntimeException("telegram down"))
                .when(telegramNotifier).broadcastMarkdown(any(), any());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void payload_correlationId_matchesResponseCorrelationId() throws Exception {
        // Operators search internal logs with the correlation ID from the Telegram
        // message — it MUST equal the one in the HTTP body.
        when(authService.login(any())).thenThrow(new RuntimeException("kaboom"));

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isInternalServerError())
                .andDo(result -> {
                    String body = result.getResponse().getContentAsString();
                    verify(telegramNotifier).broadcastMarkdown(captor.capture(),
                            org.mockito.ArgumentMatchers.eq(
                                    uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity.ERROR));
                    String payload = captor.getValue();
                    // Pull the ID from the response and confirm it appears in the payload.
                    int start = body.indexOf("\"correlationId\":\"") + 17;
                    String correlationId = body.substring(start, body.indexOf("\"", start));
                    org.junit.jupiter.api.Assertions.assertTrue(
                            payload.contains("correlation : " + correlationId),
                            "payload missing correlation id: " + correlationId);
                });
    }

    // --- Conflict (409) contract hardening ---

    @Test
    void conflict_withNullMessage_returnsNonBlankFallback() throws Exception {
        // H1: a no-arg (null-message) IllegalStateException must still produce a usable 409
        // message, not a blank one the frontend would render as an empty modal.
        when(authService.login(any())).thenThrow(new IllegalStateException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value(
                        "The request conflicts with the current state of the resource."));
    }

    @Test
    void conflict_withBlankMessage_returnsNonBlankFallback() throws Exception {
        // H1 (second guard branch): a whitespace-only message is also treated as blank.
        when(authService.login(any())).thenThrow(new IllegalStateException("   "));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(
                        "The request conflicts with the current state of the resource."));
    }

    @Test
    void configurationFault_returns500_not409() throws Exception {
        // H2: a config/infra fault (IllegalConfigurationException) is NOT an operator conflict.
        // It must fall through to the catch-all 500, never the 409 conflict handler, and must
        // not leak the internal message to the client.
        when(authService.login(any()))
                .thenThrow(new IllegalConfigurationException("Default region not found"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.startsWith("An unexpected error occurred. Reference:")));

        // Config faults must reach ops: the 500 path forwards to Telegram. This also locks the
        // routing — were IllegalConfigurationException to extend IllegalStateException it would
        // 409 (failing the assertion above) AND silently skip this alert.
        verify(telegramNotifier).broadcastMarkdown(
                argThat(p -> ((String) p).contains("IllegalConfigurationException")),
                org.mockito.ArgumentMatchers.eq(
                        uz.orientadvertise.services.common.telegram.TelegramMessageBuilder.Severity.ERROR));
    }

    @Test
    void assignmentTimeOverlap_returns409_withStructuredConflicts() throws Exception {
        // H4: AssignmentTimeOverlapException extends IllegalStateException, so Spring's
        // most-specific-handler dispatch is what keeps the structured details.conflicts payload.
        // If the dedicated handler were removed, this would still 409 but silently drop details —
        // so assert the structured payload to lock the load-bearing handler ordering.
        var overlap = new AssignmentTimeOverlapException(
                ContentAssignment.TargetType.REGION, 5L,
                List.of(new AssignmentTimeOverlapException.Conflict(
                        10L, 20L, "Summer Promo", "CONFIRMED",
                        Instant.parse("2026-06-01T00:00:00Z"), Instant.parse("2026-06-30T00:00:00Z"))));
        when(authService.login(any())).thenThrow(overlap);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"password\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.details.code").value("ASSIGNMENT_TIME_OVERLAP"))
                .andExpect(jsonPath("$.details.targetType").value("REGION"))
                .andExpect(jsonPath("$.details.conflicts").isArray())
                .andExpect(jsonPath("$.details.conflicts.length()")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.details.conflicts[0].playlistName").value("Summer Promo"));
    }
}
