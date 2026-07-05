package uz.orientadvertise.services.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.advice.GlobalExceptionHandler;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.PasswordService;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PasswordService passwordService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    // --- POST /api/me/password ----------------------------------------------

    @Test
    @WithMockUser(username = "alice")
    void changePassword_success_returns204() throws Exception {
        mockMvc.perform(post("/api/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"oldpass1","newPassword":"newpass12","confirmPassword":"newpass12"}"""))
                .andExpect(status().isNoContent());

        verify(passwordService).changeOwnPassword("alice", "oldpass1", "newpass12", "newpass12");
    }

    @Test
    @WithMockUser(username = "alice")
    void changePassword_wrongCurrent_returns400WithMessage() throws Exception {
        doThrow(new IllegalArgumentException("Current password is incorrect"))
                .when(passwordService).changeOwnPassword(anyString(), anyString(), anyString(), anyString());

        mockMvc.perform(post("/api/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrongpass","newPassword":"newpass12","confirmPassword":"newpass12"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));
    }

    @Test
    @WithMockUser(username = "alice")
    void changePassword_policyTooShort_returns400_dtoValidation() throws Exception {
        mockMvc.perform(post("/api/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"oldpass1","newPassword":"short","confirmPassword":"short"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changePassword_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"oldpass1","newPassword":"newpass12","confirmPassword":"newpass12"}"""))
                .andExpect(status().isUnauthorized());
    }

    // --- PUT /api/me/email ---------------------------------------------------

    @Test
    @WithMockUser(username = "alice")
    void setEmail_success_returns204() throws Exception {
        mockMvc.perform(put("/api/me/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"alice@example.com"}"""))
                .andExpect(status().isNoContent());

        verify(passwordService).setOwnEmail("alice", "alice@example.com");
    }

    @Test
    @WithMockUser(username = "alice")
    void setEmail_blank_clears_returns204() throws Exception {
        mockMvc.perform(put("/api/me/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":""}"""))
                .andExpect(status().isNoContent());

        verify(passwordService).setOwnEmail("alice", "");
    }

    @Test
    @WithMockUser(username = "alice")
    void setEmail_duplicate_returns409() throws Exception {
        doThrow(new IllegalStateException("That email is already in use."))
                .when(passwordService).setOwnEmail(anyString(), anyString());

        mockMvc.perform(put("/api/me/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"taken@example.com"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("That email is already in use."));
    }

    @Test
    @WithMockUser(username = "alice")
    void setEmail_invalidFormat_returns400() throws Exception {
        mockMvc.perform(put("/api/me/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email"}"""))
                .andExpect(status().isBadRequest());
    }
}
