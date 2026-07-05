package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.controller.ApiKeyAdminController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.ApiKey;
import uz.orientadvertise.services.service.ApiKeyManagementService;
import uz.orientadvertise.services.service.ApiKeyManagementService.CreatedKey;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiKeyAdminController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ApiKeyAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ApiKeyManagementService managementService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_returnsRawKeyOnce() throws Exception {
        when(managementService.create(eq("Acme")))
                .thenReturn(new CreatedKey(1L, "secretRawKey-43chars-base64url-aaaaaaaaaa", "secretRa",
                        "Acme", Instant.now().toString()));

        mockMvc.perform(post("/api/admin/api-keys")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientName":"Acme"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.rawKey").value("secretRawKey-43chars-base64url-aaaaaaaaaa"))
                .andExpect(jsonPath("$.prefix").value("secretRa"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void create_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/api-keys")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientName":"Acme"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_blankClientName_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/api-keys")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientName":""}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revoke_returnsRevokedSummary() throws Exception {
        var key = mock(ApiKey.class);
        when(key.getId()).thenReturn(1L);
        when(key.getKeyPrefix()).thenReturn("pfx12345");
        when(key.getClientName()).thenReturn("Acme");
        when(key.getStatus()).thenReturn(ApiKey.Status.REVOKED);
        when(key.getCreatedAt()).thenReturn(Instant.now());
        when(key.getRevokedAt()).thenReturn(Instant.now());
        when(key.getRevokedBy()).thenReturn("admin");
        when(managementService.revoke(eq(1L), eq("user"))).thenReturn(key);

        mockMvc.perform(delete("/api/admin/api-keys/1").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.prefix").value("pfx12345"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void revoke_unknown_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("ApiKey", 99L))
                .when(managementService).revoke(eq(99L), eq("user"));

        mockMvc.perform(delete("/api/admin/api-keys/99").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_returnsSummariesWithoutHash() throws Exception {
        // Hash and raw key must NEVER appear in any list response — confirmation by jsonPath.
        var key = mock(ApiKey.class);
        when(key.getId()).thenReturn(1L);
        when(key.getKeyPrefix()).thenReturn("pfx12345");
        when(key.getClientName()).thenReturn("Acme");
        when(key.getStatus()).thenReturn(ApiKey.Status.ACTIVE);
        when(key.getCreatedAt()).thenReturn(Instant.now());
        when(managementService.listAll()).thenReturn(List.of(key));

        mockMvc.perform(get("/api/admin/api-keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].prefix").value("pfx12345"))
                .andExpect(jsonPath("$[0].rawKey").doesNotExist())
                .andExpect(jsonPath("$[0].keyHash").doesNotExist());
    }
}
