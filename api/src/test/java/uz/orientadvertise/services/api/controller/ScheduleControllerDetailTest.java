package uz.orientadvertise.services.api.controller;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uz.orientadvertise.services.api.dto.ScheduleDetail;
import uz.orientadvertise.services.api.security.JwtAuthenticationFilter;
import uz.orientadvertise.services.api.security.SecurityConfig;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.audit.AuditRecorder;
import uz.orientadvertise.services.domain.auth.TokenValidator;
import uz.orientadvertise.services.domain.auth.UserActiveChecker;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.Playlist;
import uz.orientadvertise.services.domain.model.Project;
import uz.orientadvertise.services.domain.model.Schedule;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.service.ScheduleQueryService;
import uz.orientadvertise.services.service.ScheduleService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScheduleController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ScheduleControllerDetailTest {

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
    @WithMockUser(roles = "ADMIN")
    void detail_unknownId_returns404() throws Exception {
        when(scheduleQueryService.getDetail(eq(999L)))
                .thenThrow(new ResourceNotFoundException("Schedule", 999L));

        mockMvc.perform(get("/api/schedules/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void detail_softDeletedId_returns404() throws Exception {
        // Soft-delete and missing collapse to the same exception at the repository
        // layer — the API can't distinguish, by design.
        when(scheduleQueryService.getDetail(eq(42L)))
                .thenThrow(new ResourceNotFoundException("Schedule", 42L));

        mockMvc.perform(get("/api/schedules/42"))
                .andExpect(status().isNotFound());
    }

    /**
     * Mid-recurrence happy path. Computed by calling the DTO factory directly with a
     * fixed {@code now} — the controller goes through {@link Instant#now()} which is
     * non-deterministic for a unit test, so we exercise the same factory the controller
     * uses but with the time we control. Asserts the "next" occurrence skips the
     * currently-running window and points at tomorrow's occurrence start.
     */
    @Test
    void detail_dailyRepeating_midRecurrence_nextOccurrenceIsTomorrowsStart() {
        // Daily 10:00–11:00 UTC starting 2026-01-01, repeating until 2026-12-31.
        var start = Instant.parse("2026-01-01T10:00:00Z");
        var end = Instant.parse("2026-01-01T11:00:00Z");
        var repeatEnd = Instant.parse("2026-12-31T11:00:00Z");
        var schedule = new Schedule(
                new ContentAssignment(new Playlist(new Project("P", null), "PL", null),
                        ContentAssignment.TargetType.REGION, 1L, start, repeatEnd),
                start, end, Schedule.RepeatType.DAILY, repeatEnd);

        // "Now" sits inside the 2026-05-15 occurrence (10:30 UTC, between 10:00 and 11:00).
        var now = Instant.parse("2026-05-15T10:30:00Z");

        var detail = ScheduleDetail.from(schedule, now);

        // Mid-recurrence: the current 2026-05-15 10:00 occurrence is in progress, so the
        // returned "next" must be the start of tomorrow's occurrence — NOT today's start.
        var expectedNext = Instant.parse("2026-05-16T10:00:00Z");
        assertEquals(expectedNext, detail.nextOccurrenceUtc(),
                "mid-recurrence should advance past the in-progress window");
        assertEquals("DAILY", detail.repeatType());
        assertEquals(start, detail.startTimeUtc());
        assertEquals(end, detail.endTimeUtc());
        assertEquals(repeatEnd, detail.repeatEndUtc());
    }

    /**
     * Boundary check: when {@code now} is strictly before the first occurrence, the
     * "next" is the first occurrence itself.
     */
    @Test
    void detail_dailyRepeating_beforeFirstOccurrence_nextIsFirstOccurrence() {
        var start = Instant.parse("2026-06-01T10:00:00Z");
        var end = Instant.parse("2026-06-01T11:00:00Z");
        var schedule = new Schedule(
                new ContentAssignment(new Playlist(new Project("P", null), "PL", null),
                        ContentAssignment.TargetType.REGION, 1L, start, end),
                start, end, Schedule.RepeatType.DAILY,
                Instant.parse("2026-12-31T11:00:00Z"));

        var detail = ScheduleDetail.from(schedule, Instant.parse("2026-05-30T00:00:00Z"));

        assertEquals(start, detail.nextOccurrenceUtc());
    }

    /**
     * Past-only NONE schedule: no future occurrences → null. Documents the contract for
     * the FE so it can hide the "next run" label rather than render an empty value.
     */
    @Test
    void detail_noneTypeInPast_nextOccurrenceIsNull() {
        var start = Instant.parse("2024-01-01T10:00:00Z");
        var end = Instant.parse("2024-01-01T11:00:00Z");
        var schedule = new Schedule(
                new ContentAssignment(new Playlist(new Project("P", null), "PL", null),
                        ContentAssignment.TargetType.REGION, 1L, start, end),
                start, end, Schedule.RepeatType.NONE, null);

        var detail = ScheduleDetail.from(schedule, Instant.parse("2026-05-15T12:00:00Z"));

        org.junit.jupiter.api.Assertions.assertNull(detail.nextOccurrenceUtc());
    }
}
