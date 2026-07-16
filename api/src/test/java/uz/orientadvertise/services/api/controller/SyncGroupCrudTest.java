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
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.SyncGroup;
import uz.orientadvertise.services.service.SyncGroupManagementService;
import uz.orientadvertise.services.service.SyncGroupManagementService.SyncGroupDetailView;
import uz.orientadvertise.services.service.SyncGroupManagementService.SyncGroupView;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

@WebMvcTest(SyncGroupController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class SyncGroupCrudTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SyncGroupManagementService managementService;

    @MockitoBean
    private uz.orientadvertise.services.service.SyncGroupPlaybackService playbackService;

    @MockitoBean
    private uz.orientadvertise.services.service.DeviceManagementService deviceManagementService;

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
        var view = new SyncGroupView(stubGroup(1L, 3L, "Mall", "Entrance wall"), 4);
        when(managementService.list(eq(3L), eq("ent"), any()))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/sync-groups")
                        .param("projectId", "3")
                        .param("name", "ent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].projectId").value(3))
                .andExpect(jsonPath("$.content[0].projectName").value("Mall"))
                .andExpect(jsonPath("$.content[0].name").value("Entrance wall"))
                .andExpect(jsonPath("$.content[0].deviceCount").value(4));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void list_emptyResult_returns200WithEmptyContent() throws Exception {
        when(managementService.list(any(), any(), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        mockMvc.perform(get("/api/sync-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void list_pageSizeOver100_returns400() throws Exception {
        when(managementService.list(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Page size cannot exceed 100"));

        mockMvc.perform(get("/api/sync-groups").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("100")));
    }

    // ----- detail -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void detail_returns200_memberFieldNamedStatus() throws Exception {
        var devices = List.of(
                stubDevice(101L, "SN-001", "TV-1"),
                stubDevice(102L, "SN-002", "TV-2"));
        var view = new SyncGroupDetailView(stubGroup(5L, 3L, "Mall", "Entrance wall"), devices);
        when(managementService.getDetail(5L)).thenReturn(view);
        when(deviceManagementService.computedStatuses(any())).thenReturn(java.util.Map.of(
                101L, Device.Status.ONLINE, 102L, Device.Status.OFFLINE));

        mockMvc.perform(get("/api/sync-groups/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.projectId").value(3))
                .andExpect(jsonPath("$.name").value("Entrance wall"))
                .andExpect(jsonPath("$.deviceCount").value(2))
                .andExpect(jsonPath("$.devices.length()").value(2))
                .andExpect(jsonPath("$.devices[0].id").value(101))
                .andExpect(jsonPath("$.devices[0].serialNumber").value("SN-001"))
                // Field name MUST be `status` (heartbeat-derived), never `computedStatus`.
                .andExpect(jsonPath("$.devices[0].status").value("ONLINE"))
                .andExpect(jsonPath("$.devices[0].computedStatus").doesNotExist())
                .andExpect(jsonPath("$.devices[1].status").value("OFFLINE"))
                // No volume fields on sync-group members (FE asserts their absence).
                .andExpect(jsonPath("$.devices[0].effectiveVolume").doesNotExist())
                .andExpect(jsonPath("$.devices[0].volumeOverride").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void detail_unknownOrOutOfScope_returns404() throws Exception {
        when(managementService.getDetail(99L))
                .thenThrow(new ResourceNotFoundException("SyncGroup", 99L));

        mockMvc.perform(get("/api/sync-groups/99"))
                .andExpect(status().isNotFound());
    }

    // ----- create -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void create_returns201WithEmptyDevices() throws Exception {
        var view = new SyncGroupDetailView(stubGroup(50L, 3L, "Mall", "New Point"), List.of());
        when(managementService.create(eq(3L), eq("New Point"))).thenReturn(view);

        mockMvc.perform(post("/api/sync-groups")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 3, "name": "New Point" }"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(50))
                .andExpect(jsonPath("$.name").value("New Point"))
                .andExpect(jsonPath("$.deviceCount").value(0))
                .andExpect(jsonPath("$.devices.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_duplicate_returns409WithVerbatimMessage() throws Exception {
        when(managementService.create(eq(3L), eq("Entrance wall")))
                .thenThrow(new IllegalStateException(
                        "Sync group with name 'Entrance wall' already exists in project 3"));

        mockMvc.perform(post("/api/sync-groups")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 3, "name": "Entrance wall" }"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Sync group with name 'Entrance wall' already exists in project 3"))
                // Five-field error envelope the FE guard requires.
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_unknownProject_returns404() throws Exception {
        when(managementService.create(eq(999L), any()))
                .thenThrow(new ResourceNotFoundException("Project", 999L));

        mockMvc.perform(post("/api/sync-groups")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 999, "name": "Anything" }"""))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/sync-groups")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 3, "name": "  " }"""))
                .andExpect(status().isBadRequest());
        verify(managementService, never()).create(any(), any());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void create_viewer_returns403() throws Exception {
        mockMvc.perform(post("/api/sync-groups")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 3, "name": "New" }"""))
                .andExpect(status().isForbidden());
        verify(managementService, never()).create(any(), any());
    }

    // ----- rename -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void rename_returns200WithRenamedDetail() throws Exception {
        var view = new SyncGroupDetailView(stubGroup(5L, 3L, "Mall", "Renamed"), List.of());
        when(managementService.rename(5L, "Renamed")).thenReturn(view);

        mockMvc.perform(put("/api/sync-groups/5")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Renamed" }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void rename_duplicateNewName_returns409() throws Exception {
        when(managementService.rename(eq(5L), eq("Entrance wall")))
                .thenThrow(new IllegalStateException(
                        "Sync group with name 'Entrance wall' already exists in project 3"));

        mockMvc.perform(put("/api/sync-groups/5")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Entrance wall" }"""))
                .andExpect(status().isConflict());
    }

    // ----- delete -----

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_notInUse_returns204() throws Exception {
        mockMvc.perform(delete("/api/sync-groups/5").with(csrf()))
                .andExpect(status().isNoContent());
        verify(managementService).delete(5L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_unknown_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("SyncGroup", 999L))
                .when(managementService).delete(999L);

        mockMvc.perform(delete("/api/sync-groups/999").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_hasActiveDevices_returns409WithVerbatimMessage() throws Exception {
        // The FE renders this 409 message verbatim to operators — assert the exact wording.
        doThrow(new IllegalStateException("Sync group has 3 active device(s); remove them first"))
                .when(managementService).delete(5L);

        mockMvc.perform(delete("/api/sync-groups/5").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Sync group has 3 active device(s); remove them first"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void delete_operator_returns403() throws Exception {
        mockMvc.perform(delete("/api/sync-groups/5").with(csrf()))
                .andExpect(status().isForbidden());
        verify(managementService, never()).delete(any());
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/sync-groups"))
                .andExpect(status().isUnauthorized());
    }

    // ----- helpers -----

    private static SyncGroup stubGroup(long id, long projectId, String projectName, String name) {
        var project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        when(project.getName()).thenReturn(projectName);
        var g = mock(SyncGroup.class);
        when(g.getId()).thenReturn(id);
        when(g.getProject()).thenReturn(project);
        when(g.getName()).thenReturn(name);
        when(g.getCreatedAt()).thenReturn(Instant.parse("2026-07-09T10:00:00Z"));
        return g;
    }

    private static Device stubDevice(long id, String serial, String name) {
        var d = mock(Device.class);
        when(d.getId()).thenReturn(id);
        when(d.getSerialNumber()).thenReturn(serial);
        when(d.getName()).thenReturn(name);
        return d;
    }
}
