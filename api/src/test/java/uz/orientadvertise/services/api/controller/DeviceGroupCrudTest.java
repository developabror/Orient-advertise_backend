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
import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.service.BulkRemoteActionService;
import uz.orientadvertise.services.service.DeviceGroupManagementService;
import uz.orientadvertise.services.service.DeviceGroupManagementService.DeviceGroupDetailView;
import uz.orientadvertise.services.service.DeviceGroupManagementService.DeviceGroupView;

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

@WebMvcTest(DeviceGroupController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DeviceGroupCrudTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceGroupManagementService managementService;

    @MockitoBean
    private uz.orientadvertise.services.service.DeviceManagementService deviceManagementService;

    @MockitoBean
    private BulkRemoteActionService bulkService;

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
        var view = stubView(1L, 7L, "North", "Lobby TVs", 4);
        when(managementService.list(eq(7L), eq("lob"), any()))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/device-groups")
                        .param("projectId", "7")
                        .param("name", "lob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].projectId").value(7))
                .andExpect(jsonPath("$.content[0].projectName").value("North"))
                .andExpect(jsonPath("$.content[0].name").value("Lobby TVs"))
                .andExpect(jsonPath("$.content[0].deviceCount").value(4));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void list_emptyResult_returns200WithEmptyContent() throws Exception {
        when(managementService.list(any(), any(), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        mockMvc.perform(get("/api/device-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void list_pageSizeOver100_returns400() throws Exception {
        when(managementService.list(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Page size cannot exceed 100"));

        mockMvc.perform(get("/api/device-groups").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("100")));
    }

    // ----- detail -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void detail_returns200WithDevices() throws Exception {
        var devices = List.of(
                stubDevice(101L, "SN-001", "Lobby TV 1", Device.Status.ONLINE),
                stubDevice(102L, "SN-002", "Lobby TV 2", Device.Status.OFFLINE));
        var view = new DeviceGroupDetailView(stubGroup(5L, 7L, "North", "Lobby TVs"), devices);
        when(managementService.getDetail(5L)).thenReturn(view);
        when(deviceManagementService.computedStatuses(any())).thenReturn(java.util.Map.of(
                101L, Device.Status.ONLINE, 102L, Device.Status.OFFLINE));
        when(deviceManagementService.effectiveVolumes(any())).thenReturn(java.util.Map.of(
                101L, 80, 102L, 100));

        mockMvc.perform(get("/api/device-groups/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.projectId").value(7))
                .andExpect(jsonPath("$.name").value("Lobby TVs"))
                .andExpect(jsonPath("$.deviceCount").value(2))
                .andExpect(jsonPath("$.devices.length()").value(2))
                .andExpect(jsonPath("$.devices[0].id").value(101))
                .andExpect(jsonPath("$.devices[0].serialNumber").value("SN-001"))
                .andExpect(jsonPath("$.devices[0].computedStatus").value("ONLINE"))
                .andExpect(jsonPath("$.devices[1].computedStatus").value("OFFLINE"));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void detail_softDeleted_returns404() throws Exception {
        when(managementService.getDetail(99L))
                .thenThrow(new ResourceNotFoundException("DeviceGroup", 99L));

        mockMvc.perform(get("/api/device-groups/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void detail_surfacesGroupVolume() throws Exception {
        var view = new DeviceGroupDetailView(stubGroup(5L, 7L, "North", "Lobby TVs", 55), List.of());
        when(managementService.getDetail(5L)).thenReturn(view);
        when(deviceManagementService.computedStatuses(any())).thenReturn(java.util.Map.of());
        when(deviceManagementService.effectiveVolumes(any())).thenReturn(java.util.Map.of());

        mockMvc.perform(get("/api/device-groups/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.volume").value(55));
    }

    // ----- volume -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void setVolume_operator_returns204() throws Exception {
        mockMvc.perform(put("/api/device-groups/5/volume")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "volume": 55 }"""))
                .andExpect(status().isNoContent());
        verify(managementService).setVolume(5L, 55);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void setVolume_outOfRange_returns400() throws Exception {
        mockMvc.perform(put("/api/device-groups/5/volume")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "volume": 120 }"""))
                .andExpect(status().isBadRequest());
        verify(managementService, never()).setVolume(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void setVolume_outOfScope_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("DeviceGroup", 5L))
                .when(managementService).setVolume(5L, 55);

        mockMvc.perform(put("/api/device-groups/5/volume")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "volume": 55 }"""))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void setVolume_viewer_returns403() throws Exception {
        mockMvc.perform(put("/api/device-groups/5/volume")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "volume": 55 }"""))
                .andExpect(status().isForbidden());
        verify(managementService, never()).setVolume(any(), any());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void clearVolume_operator_returns204() throws Exception {
        mockMvc.perform(delete("/api/device-groups/5/volume").with(csrf()))
                .andExpect(status().isNoContent());
        verify(managementService).clearVolume(5L);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void clearVolume_outOfScope_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("DeviceGroup", 5L))
                .when(managementService).clearVolume(5L);

        mockMvc.perform(delete("/api/device-groups/5/volume").with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ----- create -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void create_returns201WithDetail() throws Exception {
        var view = new DeviceGroupDetailView(stubGroup(50L, 7L, "North", "New Group"), List.of());
        when(managementService.create(eq(7L), eq("New Group"))).thenReturn(view);

        mockMvc.perform(post("/api/device-groups")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 7, "name": "New Group" }"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(50))
                .andExpect(jsonPath("$.name").value("New Group"))
                .andExpect(jsonPath("$.deviceCount").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_duplicate_returns409() throws Exception {
        when(managementService.create(eq(7L), eq("Lobby TVs")))
                .thenThrow(new IllegalStateException(
                        "Device group with name 'Lobby TVs' already exists in project 7"));

        mockMvc.perform(post("/api/device-groups")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 7, "name": "Lobby TVs" }"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_unknownProject_returns404() throws Exception {
        when(managementService.create(eq(999L), any()))
                .thenThrow(new ResourceNotFoundException("Project", 999L));

        mockMvc.perform(post("/api/device-groups")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 999, "name": "Anything" }"""))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/device-groups")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 7, "name": "  " }"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void create_viewer_returns403() throws Exception {
        mockMvc.perform(post("/api/device-groups")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 7, "name": "New" }"""))
                .andExpect(status().isForbidden());
        verify(managementService, never()).create(any(), any());
    }

    // ----- rename -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void rename_returns200WithRenamedDetail() throws Exception {
        var view = new DeviceGroupDetailView(stubGroup(5L, 7L, "North", "Renamed"), List.of());
        when(managementService.rename(5L, "Renamed")).thenReturn(view);

        mockMvc.perform(put("/api/device-groups/5")
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
        when(managementService.rename(eq(5L), eq("Lobby TVs")))
                .thenThrow(new IllegalStateException(
                        "Device group with name 'Lobby TVs' already exists in project 7"));

        mockMvc.perform(put("/api/device-groups/5")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Lobby TVs" }"""))
                .andExpect(status().isConflict());
    }

    // ----- delete -----

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_notInUse_returns204() throws Exception {
        mockMvc.perform(delete("/api/device-groups/5").with(csrf()))
                .andExpect(status().isNoContent());
        verify(managementService).softDelete(5L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_unknown_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("DeviceGroup", 999L))
                .when(managementService).softDelete(999L);

        mockMvc.perform(delete("/api/device-groups/999").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_hasActiveDevices_returns409WithCount() throws Exception {
        // Message format is part of the contract — the admin UI parses N to render
        // "3 active devices still attached". Asserts the exact wording.
        doThrow(new IllegalStateException("Group has 3 active device(s); reassign first"))
                .when(managementService).softDelete(5L);

        mockMvc.perform(delete("/api/device-groups/5").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Group has 3 active device(s); reassign first"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_targetedByConfirmedAssignments_returns409WithCount() throws Exception {
        doThrow(new IllegalStateException("Group is targeted by 2 assignment(s)"))
                .when(managementService).softDelete(5L);

        mockMvc.perform(delete("/api/device-groups/5").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Group is targeted by 2 assignment(s)"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void delete_operator_returns403() throws Exception {
        mockMvc.perform(delete("/api/device-groups/5").with(csrf()))
                .andExpect(status().isForbidden());
        verify(managementService, never()).softDelete(any());
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/device-groups"))
                .andExpect(status().isUnauthorized());
    }

    // ----- helpers -----

    private static DeviceGroup stubGroup(long id, long projectId, String projectName, String name) {
        return stubGroup(id, projectId, projectName, name, null);
    }

    private static DeviceGroup stubGroup(long id, long projectId, String projectName, String name,
                                         Integer volume) {
        var project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        when(project.getName()).thenReturn(projectName);
        var g = mock(DeviceGroup.class);
        when(g.getId()).thenReturn(id);
        when(g.getProject()).thenReturn(project);
        when(g.getName()).thenReturn(name);
        when(g.getVolume()).thenReturn(volume);
        when(g.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        return g;
    }

    private static DeviceGroupView stubView(long id, long projectId, String projectName,
                                              String name, long deviceCount) {
        return new DeviceGroupView(stubGroup(id, projectId, projectName, name), deviceCount);
    }

    private static Device stubDevice(long id, String serial, String name, Device.Status status) {
        var d = mock(Device.class);
        when(d.getId()).thenReturn(id);
        when(d.getSerialNumber()).thenReturn(serial);
        when(d.getName()).thenReturn(name);
        when(d.getStatus()).thenReturn(status);
        return d;
    }
}
