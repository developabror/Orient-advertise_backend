package uz.orientadvertise.services.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
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

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ContentControllerDeleteTest {

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
    void delete_admin_returns204AndCallsService() throws Exception {
        doNothing().when(managementService).softDelete(42L);

        mockMvc.perform(delete("/api/content/42").with(csrf()))
                .andExpect(status().isNoContent());

        verify(managementService).softDelete(42L);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void delete_operator_returns204() throws Exception {
        doNothing().when(managementService).softDelete(42L);

        mockMvc.perform(delete("/api/content/42").with(csrf()))
                .andExpect(status().isNoContent());

        verify(managementService).softDelete(42L);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void delete_viewer_returns403() throws Exception {
        mockMvc.perform(delete("/api/content/42").with(csrf()))
                .andExpect(status().isForbidden());
        verify(managementService, never()).softDelete(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @WithMockUser(roles = "ADVERTISER")
    void delete_advertiser_returns403() throws Exception {
        mockMvc.perform(delete("/api/content/42").with(csrf()))
                .andExpect(status().isForbidden());
        verify(managementService, never()).softDelete(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void delete_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/api/content/42").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_unknownId_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("ContentFile", 999L))
                .when(managementService).softDelete(999L);

        mockMvc.perform(delete("/api/content/999").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_alreadySoftDeleted_returns404() throws Exception {
        // Already-deleted is treated identically to "missing" — surfaces as 404 (not 204)
        // so the admin UI knows the row is no longer there and refreshes its view.
        doThrow(new ResourceNotFoundException("ContentFile", 42L))
                .when(managementService).softDelete(42L);

        mockMvc.perform(delete("/api/content/42").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_inUseByActivePlaylists_returns409WithCount() throws Exception {
        // 409 message format is part of the API contract — the admin UI parses N to
        // render "this content is in use by 3 playlists" without re-calling the API.
        doThrow(new IllegalStateException(
                "Content is in use by 3 playlist(s); remove from playlists first"))
                .when(managementService).softDelete(42L);

        mockMvc.perform(delete("/api/content/42").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Content is in use by 3 playlist(s); remove from playlists first"));
    }
}
