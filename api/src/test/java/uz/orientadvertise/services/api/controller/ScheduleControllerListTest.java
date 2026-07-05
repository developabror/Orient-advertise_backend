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
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.Schedule;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.service.ScheduleQueryService;
import uz.orientadvertise.services.service.ScheduleService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScheduleController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ScheduleControllerListTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScheduleQueryService scheduleQueryService;

    @MockitoBean
    private ScheduleService scheduleService;

    @MockitoBean
    private ContentAssignmentRepository assignmentRepository;

    @MockitoBean
    private TokenValidator tokenValidator;

    @MockitoBean
    private UserActiveChecker userActiveChecker;

    @MockitoBean
    private AuditRecorder auditRecorder;

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_emptyResult_returns200WithEmptyContentArray() throws Exception {
        when(scheduleQueryService.findFiltered(any(), any(), any(), any(), any()))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        mockMvc.perform(get("/api/schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_filterByAssignmentId_isPropagatedAndShapesResponse() throws Exception {
        var schedule = stubSchedule(1L, 7L, 99L,
                Instant.parse("2026-01-10T10:00:00Z"),
                Instant.parse("2026-01-10T11:00:00Z"),
                Schedule.RepeatType.NONE, null);
        when(scheduleQueryService.findFiltered(eq(7L), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(schedule), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/schedules").param("assignmentId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].assignmentId").value(7))
                .andExpect(jsonPath("$.content[0].playlistId").value(99))
                .andExpect(jsonPath("$.content[0].repeatType").value("NONE"))
                .andExpect(jsonPath("$.content[0].startTimeUtc").value("2026-01-10T10:00:00Z"));

        verify(scheduleQueryService).findFiltered(eq(7L), isNull(), isNull(), isNull(),
                any(Pageable.class));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void list_dateRangeOver90Days_returns400() throws Exception {
        // ScheduleQueryService throws IllegalArgumentException; the global handler maps
        // it to 400. The exact message is part of the contract — the admin UI surfaces
        // it to the operator without parsing the JSON deeper than $.message.
        when(scheduleQueryService.findFiltered(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Date range cannot exceed 90 days"));

        mockMvc.perform(get("/api/schedules")
                        .param("from", "2025-01-01T00:00:00Z")
                        .param("to", "2025-12-31T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("90 days")));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void list_filterByRepeatType_isPropagated() throws Exception {
        var weekly = stubSchedule(2L, 7L, 99L,
                Instant.parse("2026-01-10T10:00:00Z"),
                Instant.parse("2026-01-10T11:00:00Z"),
                Schedule.RepeatType.WEEKLY,
                Instant.parse("2026-04-10T11:00:00Z"));
        when(scheduleQueryService.findFiltered(any(), eq(Schedule.RepeatType.WEEKLY), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(weekly), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/schedules").param("repeatType", "WEEKLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(2))
                .andExpect(jsonPath("$.content[0].repeatType").value("WEEKLY"))
                .andExpect(jsonPath("$.content[0].repeatEndUtc").value("2026-04-10T11:00:00Z"));

        verify(scheduleQueryService).findFiltered(isNull(), eq(Schedule.RepeatType.WEEKLY),
                isNull(), isNull(), any(Pageable.class));
    }

    private static Schedule stubSchedule(long id, long assignmentId, long playlistId,
                                          Instant start, Instant end,
                                          Schedule.RepeatType type, Instant repeatEnd) {
        var playlist = mock(Playlist.class);
        when(playlist.getId()).thenReturn(playlistId);
        var assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(assignmentId);
        when(assignment.getPlaylist()).thenReturn(playlist);
        var schedule = mock(Schedule.class);
        when(schedule.getId()).thenReturn(id);
        when(schedule.getAssignment()).thenReturn(assignment);
        when(schedule.getStartTimeUtc()).thenReturn(start);
        when(schedule.getEndTimeUtc()).thenReturn(end);
        when(schedule.getRepeatType()).thenReturn(type);
        when(schedule.getRepeatEndUtc()).thenReturn(repeatEnd);
        when(schedule.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        return schedule;
    }
}
