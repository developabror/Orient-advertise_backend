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
import uz.orientadvertise.services.domain.model.Facility;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.service.RegionManagementService;
import uz.orientadvertise.services.service.RegionManagementService.RegionDetailView;
import uz.orientadvertise.services.service.RegionManagementService.RegionView;

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

@WebMvcTest(RegionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class RegionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegionManagementService service;

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
        var view = stubView(1L, 7L, "NW", "Northwest", 3, 12);
        when(service.list(eq(7L), eq("nor"), any()))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/regions")
                        .param("projectId", "7")
                        .param("name", "nor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].projectId").value(7))
                .andExpect(jsonPath("$.content[0].code").value("NW"))
                .andExpect(jsonPath("$.content[0].name").value("Northwest"))
                .andExpect(jsonPath("$.content[0].facilityCount").value(3))
                .andExpect(jsonPath("$.content[0].deviceCount").value(12));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void list_pageSizeOver100_returns400() throws Exception {
        when(service.list(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Page size cannot exceed 100"));

        mockMvc.perform(get("/api/regions").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    // ----- detail -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void detail_returns200WithFacilities() throws Exception {
        var f1 = stubFacility(101L, "HQ", "1 Main St");
        var f2 = stubFacility(102L, "Branch", "5 Side Rd");
        var view = new RegionDetailView(stubRegion(1L, 7L, "NW", "Northwest"),
                2, 5, List.of(f1, f2));
        when(service.getDetail(1L)).thenReturn(view);

        mockMvc.perform(get("/api/regions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.code").value("NW"))
                .andExpect(jsonPath("$.facilityCount").value(2))
                .andExpect(jsonPath("$.deviceCount").value(5))
                .andExpect(jsonPath("$.facilities.length()").value(2))
                .andExpect(jsonPath("$.facilities[0].name").value("HQ"))
                .andExpect(jsonPath("$.facilities[0].address").value("1 Main St"));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void detail_unknown_returns404() throws Exception {
        when(service.getDetail(99L)).thenThrow(new ResourceNotFoundException("Region", 99L));

        mockMvc.perform(get("/api/regions/99"))
                .andExpect(status().isNotFound());
    }

    // ----- create -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void create_returns201WithDetail() throws Exception {
        var view = new RegionDetailView(stubRegion(50L, 7L, "SE", "Southeast"),
                0, 0, List.of());
        when(service.create(eq(7L), eq("SE"), eq("Southeast"))).thenReturn(view);

        mockMvc.perform(post("/api/regions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 7, "code": "SE", "name": "Southeast" }"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(50))
                .andExpect(jsonPath("$.code").value("SE"))
                .andExpect(jsonPath("$.facilityCount").value(0))
                .andExpect(jsonPath("$.deviceCount").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_duplicateCode_returns409() throws Exception {
        when(service.create(eq(7L), eq("NW"), any()))
                .thenThrow(new IllegalStateException(
                        "Region with code 'NW' already exists in project 7"));

        mockMvc.perform(post("/api/regions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 7, "code": "NW", "name": "Northwest 2" }"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_unknownProject_returns404() throws Exception {
        when(service.create(eq(999L), any(), any()))
                .thenThrow(new ResourceNotFoundException("Project", 999L));

        mockMvc.perform(post("/api/regions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 999, "code": "X", "name": "Anything" }"""))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_blankCode_returns400() throws Exception {
        mockMvc.perform(post("/api/regions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 7, "code": "  ", "name": "X" }"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void create_viewer_returns403() throws Exception {
        mockMvc.perform(post("/api/regions")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "projectId": 7, "code": "X", "name": "X" }"""))
                .andExpect(status().isForbidden());
    }

    // ----- update -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void update_renameOnly_returns200() throws Exception {
        var view = new RegionDetailView(stubRegion(1L, 7L, "NW", "Northwest Renamed"),
                0, 0, List.of());
        when(service.update(eq(1L), eq(null), eq("Northwest Renamed"))).thenReturn(view);

        mockMvc.perform(put("/api/regions/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Northwest Renamed" }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Northwest Renamed"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void update_recodeDuplicate_returns409() throws Exception {
        when(service.update(eq(1L), eq("SE"), any()))
                .thenThrow(new IllegalStateException(
                        "Region with code 'SE' already exists in project 7"));

        mockMvc.perform(put("/api/regions/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "code": "SE" }"""))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void update_blankCode_returns400() throws Exception {
        // Service rejects whitespace-only via IllegalArgumentException.
        when(service.update(eq(1L), eq("  "), any()))
                .thenThrow(new IllegalArgumentException("Code must not be blank"));

        mockMvc.perform(put("/api/regions/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "code": "  " }"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void update_codeTooLong_returns400ViaBeanValidation() throws Exception {
        // @Size(max=20) on the request record — string with 21 chars trips bean validation.
        mockMvc.perform(put("/api/regions/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "code": "AAAAAAAAAAAAAAAAAAAAA" }"""))
                .andExpect(status().isBadRequest());
        verify(service, never()).update(any(), any(), any());
    }

    // ----- delete -----

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_clean_returns204() throws Exception {
        mockMvc.perform(delete("/api/regions/1").with(csrf()))
                .andExpect(status().isNoContent());
        verify(service).delete(1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_hasActiveDevices_returns409() throws Exception {
        // Mirrors the DB-level RESTRICT constraint surfaced as a clean app-level 409
        // — the count comes from DeviceRepository.countByRegionIdAndDeletedAtIsNull.
        doThrow(new IllegalStateException("Region has 5 active device(s); reassign first"))
                .when(service).delete(1L);

        mockMvc.perform(delete("/api/regions/1").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Region has 5 active device(s); reassign first"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_hasFacilities_returns409() throws Exception {
        doThrow(new IllegalStateException("Region has 2 facility(ies); remove first"))
                .when(service).delete(1L);

        mockMvc.perform(delete("/api/regions/1").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Region has 2 facility(ies); remove first"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_unknown_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Region", 999L)).when(service).delete(999L);

        mockMvc.perform(delete("/api/regions/999").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void delete_operator_returns403() throws Exception {
        mockMvc.perform(delete("/api/regions/1").with(csrf()))
                .andExpect(status().isForbidden());
        verify(service, never()).delete(any());
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/regions"))
                .andExpect(status().isUnauthorized());
    }

    // ----- helpers -----

    private static Region stubRegion(long id, long projectId, String code, String name) {
        var project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        var r = mock(Region.class);
        when(r.getId()).thenReturn(id);
        when(r.getProject()).thenReturn(project);
        when(r.getCode()).thenReturn(code);
        when(r.getName()).thenReturn(name);
        when(r.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        return r;
    }

    private static RegionView stubView(long id, long projectId, String code, String name,
                                         long facilityCount, long deviceCount) {
        return new RegionView(stubRegion(id, projectId, code, name), facilityCount, deviceCount);
    }

    private static Facility stubFacility(long id, String name, String address) {
        var f = mock(Facility.class);
        when(f.getId()).thenReturn(id);
        when(f.getName()).thenReturn(name);
        when(f.getAddress()).thenReturn(address);
        return f;
    }
}
