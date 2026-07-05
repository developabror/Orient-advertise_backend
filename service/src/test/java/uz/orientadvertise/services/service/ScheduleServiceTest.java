package uz.orientadvertise.services.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.Schedule;
import uz.orientadvertise.services.domain.model.Schedule.RepeatType;
import uz.orientadvertise.services.domain.repository.ScheduleRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduleServiceTest {

    private ScheduleRepository scheduleRepository;
    private OperatorScopeResolver operatorScopeResolver;
    private ScheduleService service;
    private ContentAssignment assignment;

    private final Instant june1 = Instant.parse("2030-06-01T08:00:00Z");
    private final Instant june1End = Instant.parse("2030-06-01T18:00:00Z");
    private final Instant june2 = Instant.parse("2030-06-02T08:00:00Z");
    private final Instant july1 = Instant.parse("2030-07-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        scheduleRepository = mock(ScheduleRepository.class);
        operatorScopeResolver = mock(OperatorScopeResolver.class);
        // Unrestricted scope: excludes() returns false without touching the assignment graph.
        when(operatorScopeResolver.resolve()).thenReturn(
                new OperatorScopeResolver.ScopedProjects(null, null, null, false));
        service = new ScheduleService(scheduleRepository, operatorScopeResolver);
        assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(1L);
        // The create/update scope guard eagerly reads assignment.getPlaylist().getProject().getId()
        // as the excludes() argument; stub the nav chain so it doesn't NPE. Scope is unrestricted,
        // so the project id value is irrelevant.
        var playlist = mock(uz.orientadvertise.services.domain.model.Playlist.class);
        var project = mock(uz.orientadvertise.services.domain.model.Project.class);
        when(project.getId()).thenReturn(900L);
        when(playlist.getProject()).thenReturn(project);
        when(assignment.getPlaylist()).thenReturn(playlist);
    }

    @Test
    void createSchedule_noOverlap_returnsNoWarnings() {
        when(scheduleRepository.findByAssignmentIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(List.of());
        when(scheduleRepository.save(any(Schedule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = service.createSchedule(assignment, june1, june1End, RepeatType.NONE, null);

        assertNotNull(result.schedule());
        assertFalse(result.hasWarnings());
    }

    @Test
    void createSchedule_withOverlap_returnsWarningsButStillCreates() {
        var existing = new Schedule(assignment, june1, june1End, RepeatType.NONE, null);
        when(scheduleRepository.findByAssignmentIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(List.of(existing));
        when(scheduleRepository.save(any(Schedule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Create overlapping schedule — same time window
        var result = service.createSchedule(assignment, june1, june1End, RepeatType.NONE, null);

        assertNotNull(result.schedule(), "Schedule must still be created despite overlap");
        assertTrue(result.hasWarnings(), "Overlap warning must be raised");
        assertEquals(1, result.warnings().size());
    }

    @Test
    void createSchedule_dailyRepeat_detectsOverlapWithExisting() {
        // Existing: single schedule June 2 08:00-18:00
        var existing = new Schedule(assignment, june2,
                Instant.parse("2030-06-02T18:00:00Z"), RepeatType.NONE, null);
        when(scheduleRepository.findByAssignmentIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(List.of(existing));
        when(scheduleRepository.save(any(Schedule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // New: daily repeat starting June 1, which will generate June 2 occurrence
        var result = service.createSchedule(assignment, june1, june1End,
                RepeatType.DAILY, july1);

        assertTrue(result.hasWarnings(), "Daily repeat should detect overlap with June 2 schedule");
    }

    @Test
    void createSchedule_noOverlapDifferentTimes_noWarnings() {
        // Existing: June 1 morning
        var existing = new Schedule(assignment, june1, june1End, RepeatType.NONE, null);
        when(scheduleRepository.findByAssignmentIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(List.of(existing));
        when(scheduleRepository.save(any(Schedule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // New: June 2 morning — no overlap
        var result = service.createSchedule(assignment, june2,
                Instant.parse("2030-06-02T18:00:00Z"), RepeatType.NONE, null);

        assertFalse(result.hasWarnings());
    }

    @Test
    void expandOccurrences_none_returnsSingleWindow() {
        var schedule = new Schedule(assignment, june1, june1End, RepeatType.NONE, null);
        var windows = schedule.expandOccurrences(july1);

        assertEquals(1, windows.size());
        assertEquals(june1, windows.getFirst().start());
    }

    @Test
    void expandOccurrences_daily_generatesMultipleWindows() {
        var repeatEnd = june1.plus(5, ChronoUnit.DAYS);
        var schedule = new Schedule(assignment, june1, june1End, RepeatType.DAILY, repeatEnd);
        var windows = schedule.expandOccurrences(july1);

        assertEquals(5, windows.size());
        assertEquals(june1, windows.getFirst().start());
        assertEquals(june1.plus(4, ChronoUnit.DAYS), windows.getLast().start());
    }

    @Test
    void expandOccurrences_weekly_generatesWeeklyWindows() {
        var repeatEnd = june1.plus(22, ChronoUnit.DAYS);
        var schedule = new Schedule(assignment, june1, june1End, RepeatType.WEEKLY, repeatEnd);
        var windows = schedule.expandOccurrences(july1);

        // June 1, June 8, June 15, June 22 — 4 weekly occurrences before June 23
        assertEquals(4, windows.size());
    }

    @Test
    void timeWindow_overlaps_detectsCorrectly() {
        var w1 = new Schedule.TimeWindow(june1, june1End);
        var w2 = new Schedule.TimeWindow(
                Instant.parse("2030-06-01T10:00:00Z"),
                Instant.parse("2030-06-01T20:00:00Z"));
        var w3 = new Schedule.TimeWindow(june2, Instant.parse("2030-06-02T18:00:00Z"));

        assertTrue(w1.overlaps(w2));
        assertFalse(w1.overlaps(w3));
    }

    // ----- Validation: end-in-the-past -----

    @Test
    void createSchedule_endInPast_throws() {
        var pastStart = Instant.now().minus(2, ChronoUnit.DAYS);
        var pastEnd = Instant.now().minus(1, ChronoUnit.DAYS);

        org.junit.jupiter.api.Assertions.assertThrows(
                uz.orientadvertise.services.common.exception.InvalidUploadException.class,
                () -> service.createSchedule(assignment, pastStart, pastEnd, RepeatType.NONE, null));
    }

    @Test
    void createSchedule_repeatEndInPast_throws() {
        var pastStart = Instant.now().minus(30, ChronoUnit.DAYS);
        var pastEnd = pastStart.plus(1, ChronoUnit.HOURS);
        var pastRepeatEnd = Instant.now().minus(1, ChronoUnit.DAYS);

        org.junit.jupiter.api.Assertions.assertThrows(
                uz.orientadvertise.services.common.exception.InvalidUploadException.class,
                () -> service.createSchedule(assignment, pastStart, pastEnd, RepeatType.DAILY, pastRepeatEnd));
    }

    @Test
    void createSchedule_endInFuture_succeeds() {
        when(scheduleRepository.findByAssignmentIdAndDeletedAtIsNull(anyLong()))
                .thenReturn(List.of());
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createSchedule(assignment, june1, june1End, RepeatType.NONE, null);

        org.junit.jupiter.api.Assertions.assertNotNull(result.schedule());
    }

    // ----- Evaluator (Quartz job target) -----

    @Test
    void evaluateNow_countsActiveSchedules() {
        var nowSchedule = mock(Schedule.class);
        when(nowSchedule.isDeleted()).thenReturn(false);
        when(nowSchedule.getId()).thenReturn(1L);
        // expandOccurrences returns a window covering "now"
        var window = new Schedule.TimeWindow(
                Instant.now().minus(1, ChronoUnit.MINUTES),
                Instant.now().plus(1, ChronoUnit.MINUTES));
        when(nowSchedule.expandOccurrences(any())).thenReturn(List.of(window));

        var futureSchedule = mock(Schedule.class);
        when(futureSchedule.isDeleted()).thenReturn(false);
        when(futureSchedule.getId()).thenReturn(2L);
        var futureWindow = new Schedule.TimeWindow(
                Instant.now().plus(1, ChronoUnit.HOURS),
                Instant.now().plus(2, ChronoUnit.HOURS));
        when(futureSchedule.expandOccurrences(any())).thenReturn(List.of(futureWindow));

        when(scheduleRepository.findAll()).thenReturn(List.of(nowSchedule, futureSchedule));

        var result = service.evaluateNow();

        assertEquals(2, result.totalSchedules());
        assertEquals(1, result.activeNow());
    }

    @Test
    void evaluateNow_skipsDeletedSchedules() {
        var deleted = mock(Schedule.class);
        when(deleted.isDeleted()).thenReturn(true);
        when(scheduleRepository.findAll()).thenReturn(List.of(deleted));

        var result = service.evaluateNow();

        assertEquals(0, result.totalSchedules());
    }

    @Test
    void evaluateNow_emptyRepository_returnsZero() {
        when(scheduleRepository.findAll()).thenReturn(List.of());

        var result = service.evaluateNow();

        assertEquals(0, result.totalSchedules());
        assertEquals(0, result.activeNow());
        assertEquals(0, result.errors());
    }

    @Test
    void update_endInPast_throws() {
        var existing = mock(Schedule.class);
        when(existing.isDeleted()).thenReturn(false);
        // update() runs the scope guard on existing.getAssignment() before the past-window check.
        when(existing.getAssignment()).thenReturn(assignment);
        when(scheduleRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(java.util.Optional.of(existing));

        var pastStart = Instant.now().minus(2, ChronoUnit.DAYS);
        var pastEnd = Instant.now().minus(1, ChronoUnit.DAYS);

        org.junit.jupiter.api.Assertions.assertThrows(
                uz.orientadvertise.services.common.exception.InvalidUploadException.class,
                () -> service.update(1L, pastStart, pastEnd, RepeatType.NONE, null));
    }
}
