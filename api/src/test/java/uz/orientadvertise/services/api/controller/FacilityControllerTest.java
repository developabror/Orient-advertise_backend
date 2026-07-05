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
import uz.orientadvertise.services.domain.model.Facility;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.service.FacilityManagementService;
import uz.orientadvertise.services.service.FacilityManagementService.FacilityDetailView;
import uz.orientadvertise.services.service.FacilityManagementService.FacilityView;

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

@WebMvcTest(FacilityController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class FacilityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FacilityManagementService service;

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
        var view = stubView(1L, 7L, "Northwest", "HQ", 12);
        when(service.list(eq(7L), eq("hq"), any()))
                .thenReturn(new PageImpl<>(List.of(view), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/facilities")
                        .param("regionId", "7")
                        .param("name", "hq"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].regionId").value(7))
                .andExpect(jsonPath("$.content[0].regionName").value("Northwest"))
                .andExpect(jsonPath("$.content[0].name").value("HQ"))
                .andExpect(jsonPath("$.content[0].deviceCount").value(12));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void list_pageSizeOver100_returns400() throws Exception {
        when(service.list(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Page size cannot exceed 100"));

        mockMvc.perform(get("/api/facilities").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    // ----- detail -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void detail_returns200WithDevices() throws Exception {
        var d1 = stubDevice(101L, "SN-001", "Lobby TV", Device.Status.ONLINE, 55, 60);
        var d2 = stubDevice(102L, "SN-002", "Counter TV", Device.Status.OFFLINE, null, null);
        var view = new FacilityDetailView(stubFacility(1L, 7L, "Northwest", "HQ", "1 Main St"),
                List.of(d1, d2));
        when(service.getDetail(1L)).thenReturn(view);
        // Member status is the heartbeat-derived computedStatus, fetched per member id.
        when(deviceManagementService.computedStatuses(any())).thenReturn(java.util.Map.of(
                101L, Device.Status.ONLINE, 102L, Device.Status.OFFLINE));
        // Effective volume is resolved per member (override ?? group ?? 100) inside the tx and
        // passed to the DTO; without this stub FacilityDetail.from's getOrDefault NPEs on null.
        when(deviceManagementService.effectiveVolumes(any())).thenReturn(java.util.Map.of(
                101L, 60, 102L, 100));

        mockMvc.perform(get("/api/facilities/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.regionId").value(7))
                .andExpect(jsonPath("$.regionName").value("Northwest"))
                .andExpect(jsonPath("$.name").value("HQ"))
                .andExpect(jsonPath("$.address").value("1 Main St"))
                .andExpect(jsonPath("$.deviceCount").value(2))
                .andExpect(jsonPath("$.devices.length()").value(2))
                .andExpect(jsonPath("$.devices[0].id").value(101))
                .andExpect(jsonPath("$.devices[0].serialNumber").value("SN-001"))
                .andExpect(jsonPath("$.devices[0].computedStatus").value("ONLINE"))
                .andExpect(jsonPath("$.devices[0].reportedVolume").value(55))
                .andExpect(jsonPath("$.devices[0].volumeOverride").value(60))
                .andExpect(jsonPath("$.devices[0].effectiveVolume").value(60))
                .andExpect(jsonPath("$.devices[1].computedStatus").value("OFFLINE"))
                .andExpect(jsonPath("$.devices[1].effectiveVolume").value(100));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void detail_unknown_returns404() throws Exception {
        when(service.getDetail(99L)).thenThrow(new ResourceNotFoundException("Facility", 99L));

        mockMvc.perform(get("/api/facilities/99"))
                .andExpect(status().isNotFound());
    }

    // ----- create -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void create_returns201WithDetail() throws Exception {
        var view = new FacilityDetailView(stubFacility(50L, 7L, "Northwest", "Branch", null),
                List.of());
        when(service.create(eq(7L), eq("Branch"), eq(null))).thenReturn(view);

        mockMvc.perform(post("/api/facilities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "regionId": 7, "name": "Branch" }"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(50))
                .andExpect(jsonPath("$.name").value("Branch"))
                .andExpect(jsonPath("$.deviceCount").value(0));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void create_withAddress_roundTripsAddressInDetail() throws Exception {
        var view = new FacilityDetailView(
                stubFacility(51L, 7L, "Northwest", "Branch", "42 Plaza Ave, Tashkent"),
                List.of());
        when(service.create(eq(7L), eq("Branch"), eq("42 Plaza Ave, Tashkent")))
                .thenReturn(view);

        mockMvc.perform(post("/api/facilities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "regionId": 7, "name": "Branch", "address": "42 Plaza Ave, Tashkent" }"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(51))
                .andExpect(jsonPath("$.address").value("42 Plaza Ave, Tashkent"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void create_blankAddress_returns400() throws Exception {
        // Service-layer enforced (non-null but blank); controller binding would otherwise
        // accept whitespace because @Size doesn't reject it. Mirrors the rename guard.
        when(service.create(eq(7L), eq("Branch"), eq("   ")))
                .thenThrow(new IllegalArgumentException("Address must not be blank"));

        mockMvc.perform(post("/api/facilities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "regionId": 7, "name": "Branch", "address": "   " }"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_duplicateName_returns409() throws Exception {
        when(service.create(eq(7L), eq("HQ"), any()))
                .thenThrow(new IllegalStateException(
                        "Facility with name 'HQ' already exists in region 7"));

        mockMvc.perform(post("/api/facilities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "regionId": 7, "name": "HQ" }"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_unknownRegion_returns404() throws Exception {
        when(service.create(eq(999L), any(), any()))
                .thenThrow(new ResourceNotFoundException("Region", 999L));

        mockMvc.perform(post("/api/facilities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "regionId": 999, "name": "Anything" }"""))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/facilities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "regionId": 7, "name": "  " }"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void create_viewer_returns403() throws Exception {
        mockMvc.perform(post("/api/facilities")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "regionId": 7, "name": "X" }"""))
                .andExpect(status().isForbidden());
    }

    // ----- update (rename and/or change address) -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void update_nameOnly_returns200() throws Exception {
        var view = new FacilityDetailView(stubFacility(1L, 7L, "Northwest", "Renamed", null),
                List.of());
        when(service.update(1L, "Renamed", null)).thenReturn(view);

        mockMvc.perform(put("/api/facilities/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Renamed" }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void update_addressOnly_returns200_keepsName() throws Exception {
        var view = new FacilityDetailView(
                stubFacility(1L, 7L, "Northwest", "HQ", "New address"),
                List.of());
        when(service.update(1L, null, "New address")).thenReturn(view);

        mockMvc.perform(put("/api/facilities/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "address": "New address" }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("HQ"))
                .andExpect(jsonPath("$.address").value("New address"));
        verify(service).update(1L, null, "New address");
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void update_nameAndAddressTogether_returns200() throws Exception {
        var view = new FacilityDetailView(
                stubFacility(1L, 7L, "Northwest", "Renamed", "1 New Pl"),
                List.of());
        when(service.update(1L, "Renamed", "1 New Pl")).thenReturn(view);

        mockMvc.perform(put("/api/facilities/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Renamed", "address": "1 New Pl" }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.address").value("1 New Pl"));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void update_blankAddress_returns400() throws Exception {
        when(service.update(1L, null, "   "))
                .thenThrow(new IllegalArgumentException("Address must not be blank"));

        mockMvc.perform(put("/api/facilities/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "address": "   " }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Address")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void update_blankName_returns400() throws Exception {
        when(service.update(1L, "  ", null))
                .thenThrow(new IllegalArgumentException("Name must not be blank"));

        mockMvc.perform(put("/api/facilities/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "  " }"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void update_duplicate_returns409() throws Exception {
        when(service.update(eq(1L), eq("HQ"), eq(null)))
                .thenThrow(new IllegalStateException(
                        "Facility with name 'HQ' already exists in region 7"));

        mockMvc.perform(put("/api/facilities/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "HQ" }"""))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void update_payloadHasNoRegionId_isBoundIgnored() throws Exception {
        // Cross-region moves aren't supported via PUT — extra fields like regionId are
        // silently ignored by Jackson's default config rather than triggering 400. The
        // service ignores them too: update only ever uses the existing region. This test
        // documents that a stray regionId in the payload doesn't sneak through.
        var view = new FacilityDetailView(stubFacility(1L, 7L, "Northwest", "Renamed", null),
                List.of());
        when(service.update(1L, "Renamed", null)).thenReturn(view);

        mockMvc.perform(put("/api/facilities/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Renamed", "regionId": 999 }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionId").value(7)); // STILL 7, not 999
        verify(service).update(1L, "Renamed", null);
    }

    // ----- delete -----

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_clean_returns204() throws Exception {
        mockMvc.perform(delete("/api/facilities/1").with(csrf()))
                .andExpect(status().isNoContent());
        verify(service).delete(1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_hasActiveDevices_returns409() throws Exception {
        doThrow(new IllegalStateException("Facility has 4 active device(s); reassign first"))
                .when(service).delete(1L);

        mockMvc.perform(delete("/api/facilities/1").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Facility has 4 active device(s); reassign first"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_targetedByConfirmedAssignments_returns409() throws Exception {
        doThrow(new IllegalStateException("Facility is targeted by 2 assignment(s)"))
                .when(service).delete(1L);

        mockMvc.perform(delete("/api/facilities/1").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Facility is targeted by 2 assignment(s)"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_unknown_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Facility", 999L)).when(service).delete(999L);

        mockMvc.perform(delete("/api/facilities/999").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void delete_operator_returns403() throws Exception {
        mockMvc.perform(delete("/api/facilities/1").with(csrf()))
                .andExpect(status().isForbidden());
        verify(service, never()).delete(any());
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/facilities"))
                .andExpect(status().isUnauthorized());
    }

    // ----- helpers -----

    private static Facility stubFacility(long id, long regionId, String regionName,
                                            String name, String address) {
        var region = mock(Region.class);
        when(region.getId()).thenReturn(regionId);
        when(region.getName()).thenReturn(regionName);
        var f = mock(Facility.class);
        when(f.getId()).thenReturn(id);
        when(f.getRegion()).thenReturn(region);
        when(f.getName()).thenReturn(name);
        when(f.getAddress()).thenReturn(address);
        when(f.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        return f;
    }

    private static FacilityView stubView(long id, long regionId, String regionName,
                                            String name, long deviceCount) {
        return new FacilityView(stubFacility(id, regionId, regionName, name, null), deviceCount);
    }

    private static Device stubDevice(long id, String serial, String name, Device.Status status,
                                     Integer reportedVolume, Integer volumeOverride) {
        var d = mock(Device.class);
        when(d.getId()).thenReturn(id);
        when(d.getSerialNumber()).thenReturn(serial);
        when(d.getName()).thenReturn(name);
        when(d.getStatus()).thenReturn(status);
        when(d.getReportedVolume()).thenReturn(reportedVolume);
        when(d.getDesiredVolume()).thenReturn(volumeOverride);
        return d;
    }
}
