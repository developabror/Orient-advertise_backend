package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.api.ws.DeviceWebSocketHandler;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ContentControllerListTest {

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

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_emptyResult_returns200WithEmptyContentArray() throws Exception {
        // Edge case: zero matching files is NOT a 404 — the dashboard renders an empty
        // table without a special branch.
        when(listService.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        mockMvc.perform(get("/api/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_statusFilter_isPropagatedAndShapesResponse() throws Exception {
        var ready = stubFile(10L, 7L, "trailer.mp4", ContentFile.Status.READY);
        when(listService.list(any(), eq(ContentFile.Status.READY), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(viewOf(ready)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/content").param("status", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(10))
                .andExpect(jsonPath("$.content[0].projectId").value(7))
                .andExpect(jsonPath("$.content[0].name").value("trailer.mp4"))
                .andExpect(jsonPath("$.content[0].status").value("READY"))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(listService).list(isNull(), eq(ContentFile.Status.READY), isNull(), isNull(), any(), any(Pageable.class));
    }

    @Test
    @WithMockUser(username = "alice", roles = "ADVERTISER")
    void list_advertiser_isScopedToLinkedFiles() throws Exception {
        // The controller must hand the authenticated username to the service so the
        // service can resolve the user's id and scope the listing to advertiser_content_access.
        // Mocking the service to return only the linked file proves the response is
        // narrowed; the username capture proves the scoping signal was actually sent.
        var linked = stubFile(55L, 7L, "ad-spot.mp4", ContentFile.Status.READY);
        var notLinked = stubFile(56L, 7L, "private.mp4", ContentFile.Status.READY);
        when(listService.list(any(), any(), any(), eq("alice"), any(), any()))
                .thenReturn(new PageImpl<>(List.of(viewOf(linked)), PageRequest.of(0, 20), 1));
        // Defensive: ensure the unscoped path is never hit for an advertiser caller.
        when(listService.list(any(), any(), any(), isNull(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(viewOf(linked), viewOf(notLinked)), PageRequest.of(0, 20), 2));

        mockMvc.perform(get("/api/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(55))
                .andExpect(jsonPath("$.content[0].name").value("ad-spot.mp4"));

        ArgumentCaptor<String> usernameCaptor = ArgumentCaptor.forClass(String.class);
        verify(listService).list(any(), any(), any(), usernameCaptor.capture(), any(), any(Pageable.class));
        org.junit.jupiter.api.Assertions.assertEquals("alice", usernameCaptor.getValue());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_lowercaseStatusFilter_isCoercedToEnum() throws Exception {
        // FE sometimes sends the enum value lowercase (?status=ready). The case-insensitive
        // converter registered in WebMvcConfig must coerce that to ContentFile.Status.READY
        // before it hits the controller, so the service receives the proper enum value.
        var ready = stubFile(11L, 7L, "spot.mp4", ContentFile.Status.READY);
        when(listService.list(any(), eq(ContentFile.Status.READY), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(viewOf(ready)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/content").param("status", "ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(11));

        verify(listService).list(isNull(), eq(ContentFile.Status.READY), isNull(), isNull(), any(), any(Pageable.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_zeroIndexedPagination_pageEquals0ReturnsFirstPage() throws Exception {
        // Wire format is 0-based (Spring default). The FE owns the 1↔0 translation —
        // it sends `page = uiPage - 1`. So FE's first page (UI page 1) lands here as
        // ?page=0 and must resolve to internal pageNumber=0. Asserting both bounds:
        // ?page=0 → pageNumber 0, ?page=1 → pageNumber 1.
        when(listService.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 24)));

        mockMvc.perform(get("/api/content").param("page", "0").param("size", "24"))
                .andExpect(status().isOk());
        ArgumentCaptor<Pageable> firstPage = ArgumentCaptor.forClass(Pageable.class);
        verify(listService).list(any(), any(), any(), any(), any(), firstPage.capture());
        org.junit.jupiter.api.Assertions.assertEquals(0, firstPage.getValue().getPageNumber(),
                "page=0 must map to pageNumber=0 (FE sends 0-based)");
        org.junit.jupiter.api.Assertions.assertEquals(24, firstPage.getValue().getPageSize());

        org.mockito.Mockito.reset(listService);
        when(listService.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(PageRequest.of(1, 24)));

        mockMvc.perform(get("/api/content").param("page", "1").param("size", "24"))
                .andExpect(status().isOk());
        ArgumentCaptor<Pageable> secondPage = ArgumentCaptor.forClass(Pageable.class);
        verify(listService).list(any(), any(), any(), any(), any(), secondPage.capture());
        org.junit.jupiter.api.Assertions.assertEquals(1, secondPage.getValue().getPageNumber(),
                "page=1 must map to pageNumber=1 — without this, the second page silently aliases the first");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_pageSizeOver100_returns400() throws Exception {
        // The cap lives in ContentListService — the request still flows through the
        // controller, the service throws IllegalArgumentException, and the global
        // exception handler maps it to 400.
        when(listService.list(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Page size cannot exceed 100"));

        mockMvc.perform(get("/api/content").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("100")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_readyRowWithThumbnail_populatesUrlAndExpiry() throws Exception {
        var ready = stubFile(20L, 7L, "spot.mp4", ContentFile.Status.READY);
        var view = new ContentFileView(ready,
                "http://signed/thumb-1.jpg?ttl=15m",
                Instant.parse("2026-05-10T00:15:00Z"));
        when(listService.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].thumbnailUrl").value("http://signed/thumb-1.jpg?ttl=15m"))
                .andExpect(jsonPath("$.content[0].thumbnailExpiresAt").value("2026-05-10T00:15:00Z"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_readyRowWithoutThumbnail_returnsNullUrlAndNullExpiry() throws Exception {
        // Poster step failed (or hasn't run); the row stays READY but the URL fields are null.
        var ready = stubFile(21L, 7L, "no-thumb.mp4", ContentFile.Status.READY);
        when(listService.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(viewOf(ready)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("READY"))
                .andExpect(jsonPath("$.content[0].thumbnailUrl").doesNotExist())
                .andExpect(jsonPath("$.content[0].thumbnailExpiresAt").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_transcodingRow_returnsNullUrlAndNullExpiry() throws Exception {
        // Even if a thumbnail somehow exists, a non-READY row never advertises a URL.
        var transcoding = stubFile(22L, 7L, "wip.mp4", ContentFile.Status.TRANSCODING);
        when(listService.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(viewOf(transcoding)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("TRANSCODING"))
                .andExpect(jsonPath("$.content[0].thumbnailUrl").doesNotExist())
                .andExpect(jsonPath("$.content[0].thumbnailExpiresAt").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_failedRow_carriesTheTranscodeError_soTheCardCanSayWhy() throws Exception {
        // Before v1.0.134 the ffmpeg error was written to transcode_last_error and exposed on NO
        // surface — the listing showed a FAILED card with nothing on it, and the live frame carried
        // an explicit null reason. invalidReason stays null: it is the INVALID column.
        var failed = stubFile(23L, 7L, "broken.mp4", ContentFile.Status.FAILED);
        when(failed.getTranscodeLastError()).thenReturn("RuntimeException: minio down");
        when(listService.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(viewOf(failed)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].transcodeLastError")
                        .value("RuntimeException: minio down"))
                .andExpect(jsonPath("$.content[0].invalidReason").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_readyRow_carriesNoTranscodeError() throws Exception {
        // Negative: markTranscodeReady clears the column, so a healthy card must not show a stale
        // error from an earlier attempt.
        var ready = stubFile(24L, 7L, "good.mp4", ContentFile.Status.READY);
        when(listService.list(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(viewOf(ready)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transcodeLastError").doesNotExist());
    }

    /** Wrap a stubbed file in a thumbnail-less view — the common case for these tests. */
    private static ContentFileView viewOf(ContentFile file) {
        return new ContentFileView(file, null, null);
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
        when(file.getInvalidReason()).thenReturn(null);
        when(file.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(file.getUpdatedAt()).thenReturn(Instant.parse("2026-01-02T00:00:00Z"));
        return file;
    }
}
