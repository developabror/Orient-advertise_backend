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
import uz.orientadvertise.services.service.ContentRetranscodeService;
import uz.orientadvertise.services.service.ContentRetranscodeService.RetranscodeResult;
import uz.orientadvertise.services.service.ContentUploadService;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operator escape hatch: {@code POST /api/content/{id}/retranscode}. Before v1.0.132 a file
 * whose transcode dispatch was lost could not be re-driven by any endpoint, actuator surface, or
 * restart.
 */
@WebMvcTest(ContentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ContentControllerRetranscodeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentUploadService uploadService;

    @MockitoBean
    private ContentListService listService;

    @MockitoBean
    private ContentManagementService managementService;

    @MockitoBean
    private ContentRetranscodeService retranscodeService;

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
    void admin_retranscodesStuckFile_returnsNewStatus() throws Exception {
        when(retranscodeService.retranscode(anyLong(), any()))
                .thenReturn(new RetranscodeResult(42L, "TRANSCODING", "UPLOADED"));

        mockMvc.perform(post("/api/content/42/retranscode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value(42))
                // The FE renders the new status immediately rather than waiting for a poll.
                .andExpect(jsonPath("$.status").value("TRANSCODING"))
                .andExpect(jsonPath("$.previousStatus").value("UPLOADED"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operator_isAllowed() throws Exception {
        when(retranscodeService.retranscode(anyLong(), any()))
                .thenReturn(new RetranscodeResult(7L, "TRANSCODING", "FAILED"));

        mockMvc.perform(post("/api/content/7/retranscode"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewer_isForbidden() throws Exception {
        mockMvc.perform(post("/api/content/7/retranscode"))
                .andExpect(status().isForbidden());
        verify(retranscodeService, never()).retranscode(anyLong(), any());
    }

    @Test
    @WithMockUser(roles = "ADVERTISER")
    void advertiser_isForbidden() throws Exception {
        mockMvc.perform(post("/api/content/7/retranscode"))
                .andExpect(status().isForbidden());
        verify(retranscodeService, never()).retranscode(anyLong(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unknownOrDeletedFile_is404() throws Exception {
        when(retranscodeService.retranscode(anyLong(), any()))
                .thenThrow(new ResourceNotFoundException("ContentFile", 99L));

        mockMvc.perform(post("/api/content/99/retranscode"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void nonRetranscodableStatus_is409WithAMessage() throws Exception {
        // The FE depends on `message` being present on every 409 envelope.
        when(retranscodeService.retranscode(anyLong(), any()))
                .thenThrow(new IllegalStateException(
                        "Content file 5 cannot be retranscoded from status READY — only UPLOADED or FAILED files can be retried"));

        mockMvc.perform(post("/api/content/5/retranscode"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").exists());
    }

    // ---- AUTHZ-01: operator may only retry content they can see ---------------------------

    @Test
    @WithMockUser(username = "op", roles = "OPERATOR")
    void operator_onContentTheyCannotSee_is404AndNothingIsQueued() throws Exception {
        doThrow(new ResourceNotFoundException("ContentFile", 42L))
                .when(listService).assertOperatorCanAccess(42L, "op", true);

        mockMvc.perform(post("/api/content/42/retranscode"))
                .andExpect(status().isNotFound());

        verify(retranscodeService, never()).retranscode(anyLong(), any());
    }

    @Test
    @WithMockUser(username = "op", roles = "OPERATOR")
    void operator_onVisibleContent_isCheckedThenQueued() throws Exception {
        when(retranscodeService.retranscode(anyLong(), any()))
                .thenReturn(new RetranscodeResult(42L, "TRANSCODING", "FAILED"));

        mockMvc.perform(post("/api/content/42/retranscode"))
                .andExpect(status().isOk());

        verify(listService).assertOperatorCanAccess(42L, "op", true);
        verify(retranscodeService).retranscode(42L, "op");
    }
}
