package uz.orientadvertise.services.api.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
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
import uz.orientadvertise.services.domain.model.DeviceGroup;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Region;
import uz.orientadvertise.services.service.ProjectManagementService;
import uz.orientadvertise.services.service.ProjectManagementService.ProjectDetailView;
import uz.orientadvertise.services.service.ProjectManagementService.ProjectView;
import uz.orientadvertise.services.service.ProjectOperatorService;

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

@WebMvcTest(ProjectController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ProjectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectManagementService service;

    @MockitoBean
    private ProjectOperatorService projectOperatorService;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    // ----- list -----

    @Test
    @WithMockUser(roles = "VIEWER")
    void list_returnsFlatArrayWithCounts() throws Exception {
        // Unpaginated by design — response is a flat JSON array, NOT a Page envelope.
        var alpha = new ProjectView(stubProject(1L, "Alpha"), 3);
        var bravo = new ProjectView(stubProject(2L, "Bravo"), 0);
        when(service.list()).thenReturn(List.of(alpha, bravo));

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Alpha"))
                .andExpect(jsonPath("$[0].regionCount").value(3))
                .andExpect(jsonPath("$[1].name").value("Bravo"))
                .andExpect(jsonPath("$[1].regionCount").value(0));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void list_emptyResult_returnsEmptyArray() throws Exception {
        when(service.list()).thenReturn(List.of());

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void list_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized());
    }

    // ----- detail -----

    @Test
    @WithMockUser(roles = "OPERATOR")
    void detail_returns200WithRegions() throws Exception {
        var r1 = stubRegion(101L, "NW", "Northwest");
        var r2 = stubRegion(102L, "SE", "Southeast");
        var view = new ProjectDetailView(stubProject(1L, "Alpha"), 2, List.of(r1, r2), List.of());
        when(service.getDetail(1L)).thenReturn(view);

        mockMvc.perform(get("/api/projects/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Alpha"))
                .andExpect(jsonPath("$.regionCount").value(2))
                .andExpect(jsonPath("$.regions.length()").value(2))
                .andExpect(jsonPath("$.regions[0].id").value(101))
                .andExpect(jsonPath("$.regions[0].code").value("NW"))
                .andExpect(jsonPath("$.regions[0].name").value("Northwest"))
                .andExpect(jsonPath("$.regions[1].code").value("SE"))
                .andExpect(jsonPath("$.deviceGroups.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void detail_returns200WithRegionsAndDeviceGroups() throws Exception {
        // V37: GET /api/projects/{id} now embeds the project's device groups as
        // [{id,name}] (DeviceGroupBrief). Groups are project-scoped and may span regions.
        var r1 = stubRegion(101L, "NW", "Northwest");
        var g1 = stubGroup(201L, "Lobby Screens");
        var g2 = stubGroup(202L, "Entrance Banners");
        var view = new ProjectDetailView(
                stubProject(1L, "Alpha"), 1, List.of(r1), List.of(g1, g2));
        when(service.getDetail(1L)).thenReturn(view);

        mockMvc.perform(get("/api/projects/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions.length()").value(1))
                .andExpect(jsonPath("$.deviceGroups.length()").value(2))
                .andExpect(jsonPath("$.deviceGroups[0].id").value(201))
                .andExpect(jsonPath("$.deviceGroups[0].name").value("Lobby Screens"))
                .andExpect(jsonPath("$.deviceGroups[1].id").value(202))
                .andExpect(jsonPath("$.deviceGroups[1].name").value("Entrance Banners"));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void detail_unknown_returns404() throws Exception {
        when(service.getDetail(99L)).thenThrow(new ResourceNotFoundException("Project", 99L));

        mockMvc.perform(get("/api/projects/99"))
                .andExpect(status().isNotFound());
    }

    // ----- create -----

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_returns201WithDetail() throws Exception {
        var view = new ProjectDetailView(stubProject(50L, "Gamma"), 0, List.of(), List.of());
        when(service.create(eq("Gamma"))).thenReturn(view);

        mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Gamma" }"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(50))
                .andExpect(jsonPath("$.name").value("Gamma"))
                .andExpect(jsonPath("$.regionCount").value(0))
                .andExpect(jsonPath("$.regions.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_duplicateName_returns409() throws Exception {
        when(service.create(eq("Alpha")))
                .thenThrow(new IllegalStateException(
                        "Project with name 'Alpha' already exists"));

        mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Alpha" }"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "  " }"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void create_operator_returns403() throws Exception {
        // POST is ADMIN-only — operator (who CAN create regions/facilities) is rejected
        // for projects.
        mockMvc.perform(post("/api/projects")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "X" }"""))
                .andExpect(status().isForbidden());
        verify(service, never()).create(any());
    }

    // ----- rename -----

    @Test
    @WithMockUser(roles = "ADMIN")
    void rename_returns200() throws Exception {
        var view = new ProjectDetailView(stubProject(1L, "Renamed"), 0, List.of(), List.of());
        when(service.rename(1L, "Renamed")).thenReturn(view);

        mockMvc.perform(put("/api/projects/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Renamed" }"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rename_duplicate_returns409() throws Exception {
        when(service.rename(eq(1L), eq("Bravo")))
                .thenThrow(new IllegalStateException("Project with name 'Bravo' already exists"));

        mockMvc.perform(put("/api/projects/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "Bravo" }"""))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void rename_operator_returns403() throws Exception {
        mockMvc.perform(put("/api/projects/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "X" }"""))
                .andExpect(status().isForbidden());
        verify(service, never()).rename(any(), any());
    }

    // ----- delete -----

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_clean_returns204() throws Exception {
        mockMvc.perform(delete("/api/projects/1").with(csrf()))
                .andExpect(status().isNoContent());
        verify(service).delete(1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_hasRegions_returns409() throws Exception {
        // First of two delete guards: blocking regions, with the count in the message.
        doThrow(new IllegalStateException("Project has 3 region(s); remove first"))
                .when(service).delete(1L);

        mockMvc.perform(delete("/api/projects/1").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Project has 3 region(s); remove first"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_hasDeviceGroups_returns409() throws Exception {
        // Second delete guard: device groups are a direct child of the project since V37
        // (FK fk_device_group_project, no cascade), so an attached group is refused with a
        // clean 409 instead of a DB foreign-key 500.
        doThrow(new IllegalStateException("Project has 2 device group(s); remove first"))
                .when(service).delete(1L);

        mockMvc.perform(delete("/api/projects/1").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Project has 2 device group(s); remove first"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_unknown_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Project", 999L)).when(service).delete(999L);

        mockMvc.perform(delete("/api/projects/999").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void delete_operator_returns403() throws Exception {
        mockMvc.perform(delete("/api/projects/1").with(csrf()))
                .andExpect(status().isForbidden());
        verify(service, never()).delete(any());
    }

    // ----- helpers -----

    private static Project stubProject(long id, String name) {
        var p = mock(Project.class);
        when(p.getId()).thenReturn(id);
        when(p.getName()).thenReturn(name);
        when(p.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        return p;
    }

    private static Region stubRegion(long id, String code, String name) {
        var r = mock(Region.class);
        when(r.getId()).thenReturn(id);
        when(r.getCode()).thenReturn(code);
        when(r.getName()).thenReturn(name);
        when(r.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        return r;
    }

    // DeviceGroupBrief.from reads only getId()/getName() — no project mock needed.
    private static DeviceGroup stubGroup(long id, String name) {
        var g = mock(DeviceGroup.class);
        when(g.getId()).thenReturn(id);
        when(g.getName()).thenReturn(name);
        return g;
    }
}
