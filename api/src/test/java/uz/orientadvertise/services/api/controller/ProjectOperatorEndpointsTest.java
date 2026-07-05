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
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.AppUser;
import uz.orientadvertise.services.domain.model.ProjectOperator;
import uz.orientadvertise.services.service.ProjectManagementService;
import uz.orientadvertise.services.service.ProjectOperatorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Auth + status + shape matrix for the project ↔ operator assignment endpoints (ADMIN-only). */
@WebMvcTest(ProjectController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ProjectOperatorEndpointsTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ProjectManagementService service;
    @MockitoBean private ProjectOperatorService projectOperatorService;
    @MockitoBean private TokenValidator tokenValidator;
    @MockitoBean private UserActiveChecker userActiveChecker;
    @MockitoBean private AuditRecorder auditRecorder;

    private ProjectOperator operator(long userId, String username) {
        var u = mock(AppUser.class);
        when(u.getId()).thenReturn(userId);
        when(u.getUsername()).thenReturn(username);
        var po = mock(ProjectOperator.class);
        when(po.getUser()).thenReturn(u);
        when(po.getAssignedAt()).thenReturn(Instant.parse("2026-06-01T10:00:00Z"));
        when(po.getAssignedBy()).thenReturn("admin");
        return po;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listOperators_admin_returnsOperatorRefWithAuditFields() throws Exception {
        var po = operator(12L, "operator");
        when(projectOperatorService.listOperators(1L)).thenReturn(List.of(po));

        mockMvc.perform(get("/api/projects/1/operators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(12))
                .andExpect(jsonPath("$[0].username").value("operator"))
                .andExpect(jsonPath("$[0].assignedAt").value("2026-06-01T10:00:00Z"))
                .andExpect(jsonPath("$[0].assignedBy").value("admin"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void listOperators_operator_forbidden() throws Exception {
        mockMvc.perform(get("/api/projects/1/operators"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void setOperators_admin_returns200WithResultingSet() throws Exception {
        var po = operator(12L, "operator");
        when(projectOperatorService.setOperators(eq(1L), any(), eq("admin"))).thenReturn(List.of(po));

        mockMvc.perform(put("/api/projects/1/operators").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[12]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(12));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void assignOperator_admin_returns201() throws Exception {
        mockMvc.perform(post("/api/projects/1/operators/12").with(csrf()))
                .andExpect(status().isCreated());
        verify(projectOperatorService).assignOperator(eq(1L), eq(12L), eq("admin"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unassignOperator_admin_returns204() throws Exception {
        mockMvc.perform(delete("/api/projects/1/operators/12").with(csrf()))
                .andExpect(status().isNoContent());
        verify(projectOperatorService).unassignOperator(eq(1L), eq(12L));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void assignOperator_operator_forbidden() throws Exception {
        mockMvc.perform(post("/api/projects/1/operators/12").with(csrf()))
                .andExpect(status().isForbidden());
    }
}
