package uz.orientadvertise.services.api.controller;

import java.time.Instant;

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
import uz.orientadvertise.services.common.exception.AccessForbiddenException;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.ContentListService;
import uz.orientadvertise.services.service.ContentListService.StreamUrl;
import uz.orientadvertise.services.service.ContentManagementService;
import uz.orientadvertise.services.service.ContentUploadService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ContentControllerStreamUrlTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentListService listService;

    @MockitoBean
    private ContentUploadService uploadService;

    @MockitoBean
    private ContentManagementService managementService;

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
    void streamUrl_admin_returns200WithUrlAndDefaultExpiry() throws Exception {
        var expires = Instant.parse("2026-05-09T18:00:00Z");
        when(listService.streamUrl(eq(42L), any(), anyBoolean(), anyBoolean(), isNull()))
                .thenReturn(new StreamUrl("http://minio:9000/content-processed/key/42.mp4?X-Amz-...",
                        expires, "video/mp4"));

        mockMvc.perform(get("/api/content/42/stream-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value(
                        org.hamcrest.Matchers.startsWith("http://minio:9000/content-processed")))
                .andExpect(jsonPath("$.contentType").value("video/mp4"))
                .andExpect(jsonPath("$.expiresAt").value("2026-05-09T18:00:00Z"));

        verify(listService).streamUrl(eq(42L), any(), eq(false), eq(false), isNull());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void streamUrl_customExpiry_isPropagatedToService() throws Exception {
        when(listService.streamUrl(eq(42L), any(), anyBoolean(), anyBoolean(), eq(120)))
                .thenReturn(new StreamUrl("http://minio/x", Instant.parse("2026-05-09T18:02:00Z"), "video/mp4"));

        mockMvc.perform(get("/api/content/42/stream-url").param("expirySeconds", "120"))
                .andExpect(status().isOk());

        verify(listService).streamUrl(eq(42L), any(), eq(false), anyBoolean(), eq(120));
    }

    @Test
    @WithMockUser(username = "alice", roles = "ADVERTISER")
    void streamUrl_advertiser_signalsAdvertiserScopeToService() throws Exception {
        // The controller must hand callerIsAdvertiser=true so the service applies the
        // advertiser_content_access ACL. Service either returns the URL (linked) or
        // throws AccessForbiddenException → 403 (not linked) — that decision lives in
        // the service, not the controller.
        when(listService.streamUrl(eq(42L), eq("alice"), eq(true), anyBoolean(), any()))
                .thenReturn(new StreamUrl("http://minio/y", Instant.parse("2026-05-09T18:00:00Z"), "video/mp4"));

        mockMvc.perform(get("/api/content/42/stream-url"))
                .andExpect(status().isOk());

        verify(listService).streamUrl(eq(42L), eq("alice"), eq(true), eq(false), isNull());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void streamUrl_unknownContentFile_returns404() throws Exception {
        when(listService.streamUrl(eq(99L), any(), anyBoolean(), anyBoolean(), any()))
                .thenThrow(new ResourceNotFoundException("ContentFile", 99L));

        mockMvc.perform(get("/api/content/99/stream-url"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void streamUrl_notReady_returns409() throws Exception {
        // File exists but is still TRANSCODING — FE should poll then retry.
        when(listService.streamUrl(eq(42L), any(), anyBoolean(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("Content not ready for streaming, current status: TRANSCODING"));

        mockMvc.perform(get("/api/content/42/stream-url"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("not ready")));
    }

    @Test
    @WithMockUser(username = "alice", roles = "ADVERTISER")
    void streamUrl_advertiserNotLinked_returns403() throws Exception {
        when(listService.streamUrl(eq(42L), eq("alice"), eq(true), anyBoolean(), any()))
                .thenThrow(new AccessForbiddenException("Advertiser does not have access to content 42"));

        mockMvc.perform(get("/api/content/42/stream-url"))
                .andExpect(status().isForbidden());
    }

    @Test
    void streamUrl_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/content/42/stream-url"))
                .andExpect(status().isUnauthorized());
    }
}
