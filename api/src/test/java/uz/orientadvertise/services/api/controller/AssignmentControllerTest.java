package uz.orientadvertise.services.api.controller;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.advice.GlobalExceptionHandler;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.RefreshTokenCookie;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentAssignment.TargetType;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.repository.PlaylistRepository;
import uz.orientadvertise.services.service.ContentAssignmentService;
import uz.orientadvertise.services.service.exception.AssignmentTimeOverlapException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AssignmentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class, RefreshTokenCookie.class})
class AssignmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentAssignmentService assignmentService;

    @MockitoBean
    private PlaylistRepository playlistRepository;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    @WithMockUser(roles = "OPERATOR")
    void confirm_includedDeviceIds_routesToInclusionPath() throws Exception {
        var now = Instant.now();
        var draft = new ContentAssignment(new Playlist(new Project("P", null), "PL", null),
                TargetType.REGION, 1L, now, now.plusSeconds(3600), ContentAssignment.Status.CONFIRMED);
        when(assignmentService.confirmWithIncludedDevices(eq(5L), any(), any(), anyBoolean())).thenReturn(draft);

        mockMvc.perform(post("/api/assignments/5/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"includedDeviceIds\":[10,30],\"reason\":\"subset\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        verify(assignmentService).confirmWithIncludedDevices(eq(5L), eq(java.util.List.of(10L, 30L)), eq("subset"), eq(false));
        verify(assignmentService, never()).confirmWithExclusions(any(), any(), any(), anyBoolean());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void confirm_excludedDeviceIds_routesToExclusionPath() throws Exception {
        var now = Instant.now();
        var draft = new ContentAssignment(new Playlist(new Project("P", null), "PL", null),
                TargetType.REGION, 1L, now, now.plusSeconds(3600), ContentAssignment.Status.CONFIRMED);
        when(assignmentService.confirmWithExclusions(eq(5L), any(), any(), anyBoolean())).thenReturn(draft);

        mockMvc.perform(post("/api/assignments/5/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excludedDeviceIds\":[20],\"reason\":\"skip-20\"}"))
                .andExpect(status().isOk());

        verify(assignmentService).confirmWithExclusions(eq(5L), eq(java.util.List.of(20L)), eq("skip-20"), eq(false));
        verify(assignmentService, never()).confirmWithIncludedDevices(any(), any(), any(), anyBoolean());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void confirm_emptyBody_usesExclusionPathWithEmptyList() throws Exception {
        var now = Instant.now();
        var draft = new ContentAssignment(new Playlist(new Project("P", null), "PL", null),
                TargetType.REGION, 1L, now, now.plusSeconds(3600), ContentAssignment.Status.CONFIRMED);
        when(assignmentService.confirmWithExclusions(eq(5L), any(), any(), anyBoolean())).thenReturn(draft);

        mockMvc.perform(post("/api/assignments/5/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        verify(assignmentService).confirmWithExclusions(eq(5L), eq(java.util.List.of()), eq(null), eq(false));
        verify(assignmentService, never()).confirmWithIncludedDevices(any(), any(), any(), anyBoolean());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void confirm_bothListsSet_returns400() throws Exception {
        mockMvc.perform(post("/api/assignments/5/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excludedDeviceIds\":[20],\"includedDeviceIds\":[10],\"reason\":\"r\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("excludedDeviceIds or includedDeviceIds, not both")));

        verify(assignmentService, never()).confirmWithExclusions(any(), any(), any(), anyBoolean());
        verify(assignmentService, never()).confirmWithIncludedDevices(any(), any(), any(), anyBoolean());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void confirm_includedDeviceIdsOutOfScope_returns400() throws Exception {
        when(assignmentService.confirmWithIncludedDevices(eq(5L), any(), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException(
                        "includedDeviceIds contains ids outside the assignment target REGION:1: [999]"));

        mockMvc.perform(post("/api/assignments/5/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"includedDeviceIds\":[999],\"reason\":\"oops\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("outside the assignment target")));
    }

    // ===== POST /api/assignments/{id}/confirm — structured device-aware time-overlap 409 =====

    @Test
    @WithMockUser(roles = "OPERATOR")
    void confirm_timeOverlap_returns409_withStructuredConflictDetails() throws Exception {
        // Confirm is the authoritative overlap gate (createDraft no longer 409s). The 409 carries the
        // full structured details incl. the device intersection (conflictingDeviceIds).
        var t0600 = Instant.parse("2026-06-02T06:00:00Z");
        var t1800 = Instant.parse("2026-06-02T18:00:00Z");
        var t0000 = Instant.parse("2026-06-03T00:00:00Z");

        when(assignmentService.confirmWithExclusions(eq(5L), any(), any(), eq(false)))
                .thenThrow(new AssignmentTimeOverlapException(TargetType.REGION, 1L, java.util.List.of(
                        new AssignmentTimeOverlapException.Conflict(3L, 9L, "Morning ads", "CONFIRMED",
                                t0600, t1800, java.util.List.of(11L, 12L)),
                        new AssignmentTimeOverlapException.Conflict(4L, 9L, "Morning ads", "CONFIRMED",
                                t1800, t0000, java.util.List.of(13L)))));

        mockMvc.perform(post("/api/assignments/5/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$.details.code").value("ASSIGNMENT_TIME_OVERLAP"))
                .andExpect(jsonPath("$.details.targetType").value("REGION"))
                .andExpect(jsonPath("$.details.targetId").value(1))
                .andExpect(jsonPath("$.details.conflicts.length()").value(2))
                .andExpect(jsonPath("$.details.conflicts[0].id").value(3))
                .andExpect(jsonPath("$.details.conflicts[0].playlistId").value(9))
                .andExpect(jsonPath("$.details.conflicts[0].playlistName").value("Morning ads"))
                .andExpect(jsonPath("$.details.conflicts[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.details.conflicts[0].startTime").value("2026-06-02T06:00:00Z"))
                .andExpect(jsonPath("$.details.conflicts[0].endTime").value("2026-06-02T18:00:00Z"))
                .andExpect(jsonPath("$.details.conflicts[0].conflictingDeviceIds[0]").value(11))
                .andExpect(jsonPath("$.details.conflicts[0].conflictingDeviceIds[1]").value(12))
                .andExpect(jsonPath("$.details.conflicts[1].id").value(4))
                .andExpect(jsonPath("$.details.conflicts[1].conflictingDeviceIds[0]").value(13))
                .andExpect(jsonPath("$.details.conflicts[1].endTime").value("2026-06-03T00:00:00Z"))
                // message must stay non-leaky: no raw ids, no TYPE:id in prose
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("REGION:1"))))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("[3"))));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void createDraft_noOverlap_returns201_withoutDetails() throws Exception {
        var now = Instant.parse("2026-06-02T06:00:00Z");
        var draft = new ContentAssignment(new Playlist(new Project("P", null), "PL", null),
                TargetType.REGION, 1L, now, now.plusSeconds(3600), ContentAssignment.Status.DRAFT);
        when(playlistRepository.findByIdAndDeletedAtIsNull(eq(1L)))
                .thenReturn(java.util.Optional.of(new Playlist(new Project("P", null), "PL", null)));
        when(assignmentService.createDraft(any(), any(), any(), any(), any())).thenReturn(draft);

        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playlistId":1,"targetType":"REGION","targetId":1,
                                 "startTime":"2026-06-02T06:00:00Z","endTime":"2026-06-02T07:00:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void createDraft_unknownPlaylist_returns404_withoutDetailsField() throws Exception {
        // A non-overlap error must NOT carry a details field — verifies @JsonInclude(NON_NULL).
        when(playlistRepository.findByIdAndDeletedAtIsNull(eq(99L)))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/api/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"playlistId":99,"targetType":"REGION","targetId":1,
                                 "startTime":"2026-06-02T06:00:00Z","endTime":"2026-06-03T00:00:00Z"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    // ===== §3: DELETE /api/assignments/{id} (cancel) =====

    @Test
    @WithMockUser(roles = "OPERATOR")
    void cancel_operator_returns204_andSoftDeletes() throws Exception {
        mockMvc.perform(delete("/api/assignments/5"))
                .andExpect(status().isNoContent());

        verify(assignmentService).softDelete(5L);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void cancel_viewerRole_returns403() throws Exception {
        mockMvc.perform(delete("/api/assignments/5"))
                .andExpect(status().isForbidden());

        verify(assignmentService, never()).softDelete(any());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void cancel_unknownId_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("ContentAssignment", 999L))
                .when(assignmentService).softDelete(999L);

        mockMvc.perform(delete("/api/assignments/999"))
                .andExpect(status().isNotFound());
    }

    // ===== confirm with replaceConflicting (supersede) =====

    @Test
    @WithMockUser(roles = "OPERATOR")
    void confirm_replaceConflicting_threadsFlagAndReturns200() throws Exception {
        var now = Instant.now();
        var confirmed = new ContentAssignment(new Playlist(new Project("P", null), "PL", null),
                TargetType.REGION, 1L, now, now.plusSeconds(3600), ContentAssignment.Status.CONFIRMED);
        when(assignmentService.confirmWithExclusions(eq(5L), any(), any(), eq(true))).thenReturn(confirmed);

        mockMvc.perform(post("/api/assignments/5/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replaceConflicting\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.details").doesNotExist());

        verify(assignmentService).confirmWithExclusions(eq(5L), eq(java.util.List.of()), eq(null), eq(true));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void confirm_disjointSubset_returns200() throws Exception {
        // The device-aware success path: confirming against a disjoint device subset does not 409.
        var now = Instant.now();
        var confirmed = new ContentAssignment(new Playlist(new Project("P", null), "PL", null),
                TargetType.REGION, 1L, now, now.plusSeconds(3600), ContentAssignment.Status.CONFIRMED);
        when(assignmentService.confirmWithExclusions(eq(5L), any(), any(), eq(false))).thenReturn(confirmed);

        mockMvc.perform(post("/api/assignments/5/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excludedDeviceIds\":[40,50]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.details").doesNotExist());

        verify(assignmentService).confirmWithExclusions(eq(5L), eq(java.util.List.of(40L, 50L)), eq(null), eq(false));
    }
}
