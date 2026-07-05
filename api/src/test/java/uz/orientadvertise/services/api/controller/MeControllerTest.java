package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.Role;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.repository.AppUserRepository;
import uz.orientadvertise.services.service.OperatorScopeResolver;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MeController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class MeControllerTest {

    private static final String SECRET_HASH = "$2a$10$super-secret-bcrypt-hash-that-must-never-leak";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserRepository userRepository;

    @MockitoBean
    private OperatorScopeResolver operatorScopeResolver;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    @WithMockUser(username = "alice", roles = "ADMIN")
    void me_authenticatedAdmin_returns200WithProfileForAuthenticatedUsername() throws Exception {
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(42L);
        when(user.getUsername()).thenReturn("alice");
        when(user.getRole()).thenReturn(Role.ADMIN);
        when(user.isActive()).thenReturn(true);
        when(user.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(user.getEmail()).thenReturn("alice@example.com");
        when(userRepository.findByUsernameAndIsActiveTrue("alice")).thenReturn(Optional.of(user));

        mockMvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    @Test
    void me_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice", roles = "ADMIN")
    void me_responseNeverIncludesPasswordHash() throws Exception {
        // MeResponse has no password field, but this guards against future field additions
        // or a Jackson mixin accidentally exposing AppUser.password through the controller.
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(42L);
        when(user.getUsername()).thenReturn("alice");
        when(user.getRole()).thenReturn(Role.ADMIN);
        when(user.isActive()).thenReturn(true);
        when(user.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(user.getPassword()).thenReturn(SECRET_HASH);
        when(userRepository.findByUsernameAndIsActiveTrue("alice")).thenReturn(Optional.of(user));

        mockMvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString(SECRET_HASH))));
    }
}
