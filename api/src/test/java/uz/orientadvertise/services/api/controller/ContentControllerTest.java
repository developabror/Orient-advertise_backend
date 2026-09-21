package uz.orientadvertise.services.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.controller.ContentController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.api.ws.DeviceWebSocketHandler;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.ContentListService;
import uz.orientadvertise.services.service.ContentManagementService;
import uz.orientadvertise.services.service.ContentRetranscodeService;
import uz.orientadvertise.services.service.ContentUploadService;
import uz.orientadvertise.services.service.ContentUploadService.UploadResult;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ContentControllerTest {

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
    void upload_returns202WithFileIdImmediately() throws Exception {
        when(uploadService.upload(eq(1L), anyString(), anyString(), anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UploadResult(42L, "UPLOADED", "raw/uuid_movie.mp4", false, 1L));

        var file = new MockMultipartFile("file", "movie.mp4", "video/mp4", "bytes".getBytes());

        mockMvc.perform(multipart("/api/content/upload")
                        .file(file)
                        .param("projectId", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fileId").value(42))
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.storageKey").value("raw/uuid_movie.mp4"))
                .andExpect(jsonPath("$.projectId").value(1))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void upload_operatorRole_allowed() throws Exception {
        when(uploadService.upload(anyLong(), anyString(), anyString(), anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UploadResult(43L, "UPLOADED", "raw/k", false, 1L));

        var file = new MockMultipartFile("file", "f.mp4", "video/mp4", "x".getBytes());

        mockMvc.perform(multipart("/api/content/upload").file(file).param("projectId", "1"))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void upload_viewerRole_forbidden() throws Exception {
        var file = new MockMultipartFile("file", "f.mp4", "video/mp4", "x".getBytes());

        mockMvc.perform(multipart("/api/content/upload").file(file).param("projectId", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void upload_unknownProject_returns202AsOrphan() throws Exception {
        // Unknown project no longer 404s — service treats it as an orphan upload and
        // returns projectId=null on the response. The FE branches on that to prompt
        // the operator to bind a project after upload.
        when(uploadService.upload(eq(99L), anyString(), anyString(), anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UploadResult(7L, "UPLOADED", "raw/k", false, null));

        var file = new MockMultipartFile("file", "f.mp4", "video/mp4", "x".getBytes());

        mockMvc.perform(multipart("/api/content/upload").file(file).param("projectId", "99"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fileId").value(7))
                .andExpect(jsonPath("$.projectId").doesNotExist())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("orphan")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void upload_zeroProjectId_normalizedToNull_returns202AsOrphan() throws Exception {
        // FE quirk: sometimes sends projectId=0 instead of omitting the param. The
        // controller normalizes <= 0 to null at the boundary so the service never sees
        // a sentinel value and the row doesn't get looked up against id 0 (which can't
        // exist — Postgres ids start at 1).
        when(uploadService.upload(org.mockito.ArgumentMatchers.isNull(), anyString(), anyString(), anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UploadResult(9L, "UPLOADED", "raw/k", false, null));

        var file = new MockMultipartFile("file", "f.mp4", "video/mp4", "x".getBytes());

        mockMvc.perform(multipart("/api/content/upload").file(file).param("projectId", "0"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fileId").value(9))
                .andExpect(jsonPath("$.projectId").doesNotExist());

        verify(uploadService).upload(org.mockito.ArgumentMatchers.isNull(),
                eq("f.mp4"), eq("video/mp4"), anyLong(), any(), eq(false), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void upload_noProjectId_returns202AsOrphan() throws Exception {
        // Omitting projectId entirely is the FE's "I don't know the project yet" path.
        when(uploadService.upload(org.mockito.ArgumentMatchers.isNull(), anyString(), anyString(), anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UploadResult(8L, "UPLOADED", "raw/k", false, null));

        var file = new MockMultipartFile("file", "f.mp4", "video/mp4", "x".getBytes());

        mockMvc.perform(multipart("/api/content/upload").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fileId").value(8))
                .andExpect(jsonPath("$.projectId").doesNotExist());
    }

    @Test
    void upload_unauthenticated_returns401() throws Exception {
        var file = new MockMultipartFile("file", "f.mp4", "video/mp4", "x".getBytes());

        mockMvc.perform(multipart("/api/content/upload").file(file).param("projectId", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void upload_passesFilenameAndContentType_toService() throws Exception {
        when(uploadService.upload(anyLong(), anyString(), anyString(), anyLong(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new UploadResult(1L, "UPLOADED", "raw/k", false, 5L));

        var file = new MockMultipartFile("file", "vacation.mp4", "video/mp4", "data".getBytes());

        mockMvc.perform(multipart("/api/content/upload").file(file).param("projectId", "5"))
                .andExpect(status().isAccepted());

        verify(uploadService).upload(eq(5L), eq("vacation.mp4"), eq("video/mp4"),
                eq(4L), any(), eq(false), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void upload_imageContentType_returns400() throws Exception {
        var file = new MockMultipartFile("file", "img.png", "image/png", "data".getBytes());

        mockMvc.perform(multipart("/api/content/upload").file(file).param("projectId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.error").value("Invalid Upload"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void upload_disallowedExtension_returns400() throws Exception {
        var file = new MockMultipartFile("file", "movie.exe", "video/mp4", "data".getBytes());

        mockMvc.perform(multipart("/api/content/upload").file(file).param("projectId", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void upload_zeroSize_returns400() throws Exception {
        var file = new MockMultipartFile("file", "movie.mp4", "video/mp4", new byte[0]);

        mockMvc.perform(multipart("/api/content/upload").file(file).param("projectId", "1"))
                .andExpect(status().isBadRequest());
    }
}
