package uz.orientadvertise.services.api.controller;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.controller.FileController;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.StorageUnavailableException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.service.FileStorageService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.security.test.context.support.WithMockUser;

@WebMvcTest(FileController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@WithMockUser(roles = "ADMIN")
class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileStorageService fileStorageService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    void upload_returnsOkWithObjectName() throws Exception {
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes());

        mockMvc.perform(multipart("/api/files").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectName").exists())
                .andExpect(jsonPath("$.size").value(7));
    }

    @Test
    void upload_returns503WhenStorageUnavailable() throws Exception {
        doThrow(new StorageUnavailableException("MinIO unavailable"))
                .when(fileStorageService).upload(anyString(), any(), anyLong(), anyString());

        var file = new MockMultipartFile("file", "test.txt", "text/plain", "content".getBytes());

        mockMvc.perform(multipart("/api/files").file(file))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void download_returnsFileStream() throws Exception {
        when(fileStorageService.download("file.txt"))
                .thenReturn(new ByteArrayInputStream("data".getBytes()));

        mockMvc.perform(get("/api/files/file.txt"))
                .andExpect(status().isOk());
    }

    @Test
    void presignedUrl_returnsUrl() throws Exception {
        when(fileStorageService.generatePresignedUrl("file.txt"))
                .thenReturn("http://minio:9000/uploads/file.txt?token=abc");

        mockMvc.perform(get("/api/files/file.txt/presigned-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("http://minio:9000/uploads/file.txt?token=abc"));
    }

    @Test
    void presignedUrl_returns503WhenDegraded() throws Exception {
        when(fileStorageService.generatePresignedUrl("file.txt"))
                .thenThrow(new StorageUnavailableException("MinIO unavailable"));

        mockMvc.perform(get("/api/files/file.txt/presigned-url"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void status_returns200WhenAvailable() throws Exception {
        when(fileStorageService.isStorageAvailable()).thenReturn(true);

        mockMvc.perform(get("/api/files/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void status_returns503WhenDegraded() throws Exception {
        when(fileStorageService.isStorageAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/files/status"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DEGRADED"));
    }

    @Test
    void delete_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/files/file.txt"))
                .andExpect(status().isNoContent());
    }
}
