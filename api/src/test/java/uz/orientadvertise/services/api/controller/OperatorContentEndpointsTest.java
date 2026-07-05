package uz.orientadvertise.services.api.controller;

import java.util.List;

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
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.service.AdvertiserContentService;
import uz.orientadvertise.services.service.OperatorContentService;
import uz.orientadvertise.services.service.UserManagementService;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auth + status matrix for the operator content-grant endpoints (ADMIN-only). The pivotal case
 * is that the GET is NOT operator-readable — an operator must not be able to read any user's grant
 * list (the endpoint is {@code hasAnyRole('ADMIN')}, NOT a copy of the advertiser GET's
 * {@code hasAnyRole('ADMIN','OPERATOR')}).
 */
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class OperatorContentEndpointsTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private UserManagementService userManagementService;
    @MockitoBean private AdvertiserContentService advertiserContentService;
    @MockitoBean private OperatorContentService operatorContentService;
    @MockitoBean private TokenValidator tokenValidator;
    @MockitoBean private UserActiveChecker userActiveChecker;
    @MockitoBean private AuditRecorder auditRecorder;

    private ContentFile content(long id, String name) {
        var c = mock(ContentFile.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getStatus()).thenReturn(ContentFile.Status.READY);
        return c;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listOperatorContent_admin_returns200WithLinkedContent() throws Exception {
        var file = content(88L, "promo.mp4");
        when(operatorContentService.getAccessibleContent(5L)).thenReturn(List.of(file));

        mockMvc.perform(get("/api/users/5/operator-content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(88))
                .andExpect(jsonPath("$[0].name").value("promo.mp4"))
                .andExpect(jsonPath("$[0].status").value("READY"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void listOperatorContent_operator_forbidden() throws Exception {
        // CRITICAL: grant management is admin-only — an operator must NOT read grant lists.
        mockMvc.perform(get("/api/users/5/operator-content"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void linkOperatorContent_admin_returns201() throws Exception {
        mockMvc.perform(post("/api/users/5/operator-content/88").with(csrf()))
                .andExpect(status().isCreated());
        verify(operatorContentService).linkContent(eq(5L), eq(88L), eq("admin"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void linkOperatorContent_operator_forbidden() throws Exception {
        mockMvc.perform(post("/api/users/5/operator-content/88").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unlinkOperatorContent_admin_returns204() throws Exception {
        mockMvc.perform(delete("/api/users/5/operator-content/88").with(csrf()))
                .andExpect(status().isNoContent());
        verify(operatorContentService).unlinkContent(eq(5L), eq(88L));
    }
}
