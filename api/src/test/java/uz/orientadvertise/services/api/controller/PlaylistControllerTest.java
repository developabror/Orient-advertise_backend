package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.PlaylistItem;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.service.PlaylistItemService;
import uz.orientadvertise.services.service.PlaylistManagementService;
import uz.orientadvertise.services.service.PlaylistManagementService.PlaylistDetailView;
import uz.orientadvertise.services.service.PlaylistManagementService.PlaylistStats;
import uz.orientadvertise.services.service.PlaylistManagementService.PlaylistView;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlaylistController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PlaylistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlaylistManagementService service;

    @MockitoBean
    private PlaylistItemService itemService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    // ----- list -----

    @Test
    @WithMockUser(roles = "VIEWER")
    void list_withFilters_propagatesAndShapesResponse() throws Exception {
        var view = stubView(1L, 7L, "Lobby loop", 3, 180);
        when(service.list(eq(7L), eq("lob"), any()))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/playlists")
                        .param("projectId", "7")
                        .param("name", "lob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].projectId").value(7))
                .andExpect(jsonPath("$.content[0].name").value("Lobby loop"))
                .andExpect(jsonPath("$.content[0].itemCount").value(3))
                .andExpect(jsonPath("$.content[0].totalDurationSeconds").value(180))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void list_emptyResult_returns200WithEmptyContent() throws Exception {
        when(service.list(any(), any(), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        mockMvc.perform(get("/api/playlists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void list_pageSizeOver100_returns400() throws Exception {
        when(service.list(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Page size cannot exceed 100"));

        mockMvc.perform(get("/api/playlists").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("100")));
    }

    // ----- detail -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void detail_returns200WithItemsSortedByPosition() throws Exception {
        var item1 = stubItem(10L, 0, 100L, "ad1.mp4", 30, 25);
        var item2 = stubItem(11L, 1, 101L, "ad2.mp4", 60, null);
        var view = new PlaylistDetailView(stubPlaylist(5L, 7L, "Loop"),
                List.of(item1, item2), 25 + 60);
        when(service.getDetail(5L)).thenReturn(view);

        mockMvc.perform(get("/api/playlists/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.name").value("Loop"))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.totalDurationSeconds").value(85))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].position").value(0))
                .andExpect(jsonPath("$.items[0].contentFileName").value("ad1.mp4"))
                .andExpect(jsonPath("$.items[0].durationSeconds").value(30))
                .andExpect(jsonPath("$.items[0].durationOverride").value(25))
                .andExpect(jsonPath("$.items[1].durationOverride").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void detail_softDeleted_returns404() throws Exception {
        // The service collapses missing/soft-deleted into the same exception, so the
        // controller cannot — and intentionally does not — distinguish them.
        when(service.getDetail(99L))
                .thenThrow(new ResourceNotFoundException("Playlist", 99L));

        mockMvc.perform(get("/api/playlists/99"))
                .andExpect(status().isNotFound());
    }

    // ----- create -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void create_returns201WithDetail() throws Exception {
        var view = new PlaylistDetailView(stubPlaylist(50L, 7L, "Morning"), List.of(), 0L);
        when(service.create(eq(7L), eq("Morning"))).thenReturn(view);

        mockMvc.perform(post("/api/playlists")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 7, "name": "Morning" }"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(50))
                .andExpect(jsonPath("$.name").value("Morning"))
                .andExpect(jsonPath("$.itemCount").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_duplicateNameInProject_returns409() throws Exception {
        // Service translates the duplicate to IllegalStateException → 409 via the global
        // handler. Asserts the contract message exposed to the FE.
        when(service.create(eq(7L), eq("Morning")))
                .thenThrow(new IllegalStateException(
                        "Playlist with name 'Morning' already exists in project 7"));

        mockMvc.perform(post("/api/playlists")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 7, "name": "Morning" }"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/playlists")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 7, "name": "  " }"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void create_viewer_returns403() throws Exception {
        mockMvc.perform(post("/api/playlists")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 7, "name": "Morning" }"""))
                .andExpect(status().isForbidden());
    }

    // ----- rename -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void rename_duplicateNewName_returns409() throws Exception {
        when(service.rename(eq(5L), eq("Morning")))
                .thenThrow(new IllegalStateException(
                        "Playlist with name 'Morning' already exists in project 7"));

        mockMvc.perform(put("/api/playlists/5")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Morning" }"""))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void rename_returns200WithRenamedDetail() throws Exception {
        var view = new PlaylistDetailView(stubPlaylist(5L, 7L, "Evening"), List.of(), 0L);
        when(service.rename(5L, "Evening")).thenReturn(view);

        mockMvc.perform(put("/api/playlists/5")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Evening" }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Evening"));
    }

    // ----- delete -----

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_inUseByActiveAssignments_returns409WithCount() throws Exception {
        // Message is part of the contract — the admin UI parses the count to render
        // "in use by N active assignment(s)" without a second round trip.
        doThrow(new IllegalStateException("Playlist is in use by 2 active assignment(s)"))
                .when(service).softDelete(5L);

        mockMvc.perform(delete("/api/playlists/5").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Playlist is in use by 2 active assignment(s)"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_notInUse_returns204() throws Exception {
        mockMvc.perform(delete("/api/playlists/5").with(csrf()))
                .andExpect(status().isNoContent());
        verify(service).softDelete(5L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_unknownId_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Playlist", 999L))
                .when(service).softDelete(999L);

        mockMvc.perform(delete("/api/playlists/999").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void delete_operator_returns403() throws Exception {
        mockMvc.perform(delete("/api/playlists/5").with(csrf()))
                .andExpect(status().isForbidden());
        verify(service, never()).softDelete(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/playlists"))
                .andExpect(status().isUnauthorized());
    }

    // ----- helpers -----

    private static Playlist stubPlaylist(long id, long projectId, String name) {
        var project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        var p = mock(Playlist.class);
        when(p.getId()).thenReturn(id);
        when(p.getProject()).thenReturn(project);
        when(p.getName()).thenReturn(name);
        when(p.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(p.getUpdatedAt()).thenReturn(Instant.parse("2026-01-02T00:00:00Z"));
        return p;
    }

    private static PlaylistView stubView(long id, long projectId, String name,
                                           long itemCount, long totalDuration) {
        return new PlaylistView(stubPlaylist(id, projectId, name),
                new PlaylistStats(itemCount, totalDuration));
    }

    private static PlaylistItem stubItem(long id, int position, long contentFileId,
                                           String contentFileName, Integer naturalDuration,
                                           Integer override) {
        var cf = mock(ContentFile.class);
        when(cf.getId()).thenReturn(contentFileId);
        when(cf.getName()).thenReturn(contentFileName);
        when(cf.getDurationSeconds()).thenReturn(naturalDuration);
        var item = mock(PlaylistItem.class);
        when(item.getId()).thenReturn(id);
        when(item.getPosition()).thenReturn(position);
        when(item.getContentFile()).thenReturn(cf);
        when(item.getDurationSeconds()).thenReturn(override);
        return item;
    }
}
