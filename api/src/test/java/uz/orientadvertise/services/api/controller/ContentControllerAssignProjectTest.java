package uz.orientadvertise.services.api.controller;

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
import uz.orientadvertise.services.api.ws.DeviceWebSocketHandler;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.ContentListService;
import uz.orientadvertise.services.service.ContentManagementService;
import uz.orientadvertise.services.service.ContentUploadService;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ContentControllerAssignProjectTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentManagementService managementService;

    @MockitoBean
    private ContentListService listService;

    @MockitoBean
    private ContentUploadService uploadService;

    @MockitoBean
    private DeviceWebSocketHandler webSocketHandler;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    @WithMockUser(roles = "ADMIN")
    void assignProject_admin_returns204() throws Exception {
        doNothing().when(managementService).assignProject(eq(7L), eq(5L));

        mockMvc.perform(patch("/api/content/7/project")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\": 5}"))
                .andExpect(status().isNoContent());

        verify(managementService).assignProject(7L, 5L);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void assignProject_zeroProjectId_normalizedToNull_clearsBinding() throws Exception {
        // projectId=0 is the FE's "no project" sentinel. Controller coerces it to null,
        // so the service receives null and clears the binding (orphan again) instead
        // of trying — and failing — to look up project id 0.
        doNothing().when(managementService).assignProject(eq(7L), eq(null));

        mockMvc.perform(patch("/api/content/7/project")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\": 0}"))
                .andExpect(status().isNoContent());

        verify(managementService).assignProject(7L, null);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void assignProject_nullProjectId_clearsBinding() throws Exception {
        // Body with explicit null projectId clears the binding (orphan again).
        doNothing().when(managementService).assignProject(eq(7L), eq(null));

        mockMvc.perform(patch("/api/content/7/project")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\": null}"))
                .andExpect(status().isNoContent());

        verify(managementService).assignProject(7L, null);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void assignProject_viewer_forbidden() throws Exception {
        mockMvc.perform(patch("/api/content/7/project")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\": 5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void assignProject_unauthenticated_returns401() throws Exception {
        mockMvc.perform(patch("/api/content/7/project")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\": 5}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void assignProject_unknownContentFile_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("ContentFile", 99L))
                .when(managementService).assignProject(eq(99L), eq(5L));

        mockMvc.perform(patch("/api/content/99/project")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\": 5}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void assignProject_unknownProject_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Project", 999L))
                .when(managementService).assignProject(eq(7L), eq(999L));

        mockMvc.perform(patch("/api/content/7/project")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\": 999}"))
                .andExpect(status().isNotFound());
    }
}
