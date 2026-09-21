package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.service.ContentListService;
import uz.orientadvertise.services.service.ContentListService.ContentFileView;
import uz.orientadvertise.services.service.ContentManagementService;
import uz.orientadvertise.services.service.ContentRetranscodeService;
import uz.orientadvertise.services.service.ContentUploadService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ContentControllerDetailTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentListService listService;

    @MockitoBean
    private ContentUploadService uploadService;

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

    static Stream<ContentFile.Status> adminReadableStatuses() {
        // ADMIN can fetch detail across the whole status surface — including FAILED and
        // INVALID, which the player never serves, because operators investigating a broken
        // upload need to see why it was rejected.
        return Stream.of(ContentFile.Status.READY, ContentFile.Status.FAILED, ContentFile.Status.INVALID);
    }

    @ParameterizedTest
    @MethodSource("adminReadableStatuses")
    @WithMockUser(roles = "ADMIN")
    void detail_admin_reads_anyStatus(ContentFile.Status status) throws Exception {
        var file = stubFile(42L, 7L, "movie.mp4", status);
        if (status == ContentFile.Status.INVALID) {
            when(file.getInvalidReason()).thenReturn("codec mismatch");
        }
        when(listService.getDetail(eq(42L), any(), anyBoolean(), anyBoolean())).thenReturn(viewOf(file));

        mockMvc.perform(get("/api/content/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.projectId").value(7))
                .andExpect(jsonPath("$.name").value("movie.mp4"))
                .andExpect(jsonPath("$.status").value(status.name()))
                // content-5: storage internals are no longer exposed on the wire DTO.
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.processedStorageKey").doesNotExist())
                .andExpect(jsonPath("$.thumbnailStorageKey").doesNotExist())
                .andExpect(jsonPath("$.checksum").doesNotExist())
                // No thumbnail set on the stub → URL fields stay null in the JSON.
                .andExpect(jsonPath("$.thumbnailUrl").doesNotExist())
                .andExpect(jsonPath("$.thumbnailExpiresAt").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void detail_unknownId_returns404() throws Exception {
        when(listService.getDetail(eq(999L), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new ResourceNotFoundException("ContentFile", 999L));

        mockMvc.perform(get("/api/content/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "alice", roles = "ADVERTISER")
    void detail_advertiser_withoutGrant_returns403() throws Exception {
        // Service confirmed the row exists, then rejected on access — controller surfaces
        // 403, not 404. This is the path that prevents existence leaks to advertisers.
        when(listService.getDetail(eq(42L), eq("alice"), eq(true), eq(false)))
                .thenThrow(new AccessForbiddenException(
                        "Advertiser does not have access to content 42"));

        mockMvc.perform(get("/api/content/42"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("does not have access")));
    }

    @Test
    @WithMockUser(username = "alice", roles = "ADVERTISER")
    void detail_advertiser_withGrant_returns200() throws Exception {
        var file = stubFile(42L, 7L, "ad-spot.mp4", ContentFile.Status.READY);
        when(listService.getDetail(eq(42L), eq("alice"), eq(true), eq(false))).thenReturn(viewOf(file));

        mockMvc.perform(get("/api/content/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.name").value("ad-spot.mp4"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void detail_readyWithThumbnail_populatesUrlAndExpiry() throws Exception {
        var file = stubFile(50L, 7L, "spot.mp4", ContentFile.Status.READY);
        var view = new ContentFileView(file,
                "http://signed/thumb-50.jpg?ttl=15m",
                Instant.parse("2026-05-10T00:15:00Z"));
        when(listService.getDetail(eq(50L), any(), anyBoolean(), anyBoolean())).thenReturn(view);

        mockMvc.perform(get("/api/content/50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thumbnailUrl").value("http://signed/thumb-50.jpg?ttl=15m"))
                .andExpect(jsonPath("$.thumbnailExpiresAt").value("2026-05-10T00:15:00Z"));
    }

    /** Wrap a stubbed file in a thumbnail-less view — the common case for these tests. */
    private static ContentFileView viewOf(ContentFile file) {
        return new ContentFileView(file, null, null);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void detail_softDeleted_returns404() throws Exception {
        // Service treats soft-deleted rows as gone — admins also see 404, matching the
        // contract that a deletedAt-stamped row is invisible to every caller.
        when(listService.getDetail(eq(42L), any(), anyBoolean(), anyBoolean()))
                .thenThrow(new ResourceNotFoundException("ContentFile", 42L));

        mockMvc.perform(get("/api/content/42"))
                .andExpect(status().isNotFound());
    }

    private static ContentFile stubFile(long id, long projectId, String name, ContentFile.Status status) {
        var project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        var file = mock(ContentFile.class);
        when(file.getId()).thenReturn(id);
        when(file.getProject()).thenReturn(project);
        when(file.getName()).thenReturn(name);
        when(file.getContentType()).thenReturn("video/mp4");
        when(file.getSizeBytes()).thenReturn(1024L);
        when(file.getDurationSeconds()).thenReturn(30);
        when(file.getStatus()).thenReturn(status);
        when(file.getStorageKey()).thenReturn("raw/" + name);
        when(file.getProcessedStorageKey()).thenReturn("hls/" + name.replace(".mp4", ".m3u8"));
        when(file.getChecksum()).thenReturn("sha256:abc");
        when(file.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(file.getUpdatedAt()).thenReturn(Instant.parse("2026-01-02T00:00:00Z"));
        when(file.getDeletedAt()).thenReturn(null);
        return file;
    }
}
