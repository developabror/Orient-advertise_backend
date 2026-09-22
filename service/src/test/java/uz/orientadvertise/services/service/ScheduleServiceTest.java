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
        when(nowSchedule.expandOccurrences(any(), any())).thenReturn(List.of(window));

        var futureSchedule = mock(Schedule.class);
        when(futureSchedule.isDeleted()).thenReturn(false);
        when(futureSchedule.getId()).thenReturn(2L);
        var futureWindow = new Schedule.TimeWindow(
                Instant.now().plus(1, ChronoUnit.HOURS),
                Instant.now().plus(2, ChronoUnit.HOURS));
        when(futureSchedule.expandOccurrences(any(), any())).thenReturn(List.of(futureWindow));

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

    // ----- LOGIC-12: window validation -----

    private void assertRejected(Instant start, Instant end, RepeatType type, Instant repeatEnd) {
        org.junit.jupiter.api.Assertions.assertThrows(
                uz.orientadvertise.services.common.exception.InvalidUploadException.class,
                () -> service.createSchedule(assignment, start, end, type, repeatEnd));
    }

    @Test
    void createSchedule_endBeforeOrAtStart_throws() {
        assertRejected(june1End, june1, RepeatType.NONE, null);
        assertRejected(june1, june1, RepeatType.NONE, null);
    }

    @Test
    void createSchedule_repeatingWindowLongerThanItsInterval_throws() {
        assertRejected(june1, june1.plus(25, ChronoUnit.HOURS), RepeatType.DAILY, null);
        assertRejected(june1, june1.plus(8, ChronoUnit.DAYS), RepeatType.WEEKLY, null);
        assertRejected(june1, june1.plus(29, ChronoUnit.DAYS), RepeatType.MONTHLY, null);
    }

    @Test
    void createSchedule_repeatEndNotAfterStart_throws() {
        assertRejected(june1, june1End, RepeatType.DAILY, june1);
    }

    @Test
    void createSchedule_repeatingWindowOfExactlyOneInterval_isAccepted() {
        when(scheduleRepository.findByAssignmentIdAndDeletedAtIsNull(anyLong())).thenReturn(List.of());
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createSchedule(assignment, june1, june1.plus(1, ChronoUnit.DAYS),
                RepeatType.DAILY, july1);

        assertNotNull(result.schedule());
    }

    // ----- LOGIC-12: bounded, arithmetic expansion -----

    @Test
    void expandOccurrences_scheduleFromYearOne_startsAtFrom_notAtTheBeginningOfTime() {
        var ancient = new Schedule(assignment, Instant.parse("0001-01-01T08:00:00Z"),
                Instant.parse("0001-01-01T18:00:00Z"), RepeatType.DAILY, null);

        var windows = ancient.expandOccurrences(june1, june1.plus(3, ChronoUnit.DAYS));

        // June 1 08:00 has not ended at June 1 08:00, so it is the first; then June 2 and 3.
        assertEquals(3, windows.size());
        assertEquals(june1, windows.getFirst().start());
        assertEquals(june1End, windows.getFirst().end());
    }

    @Test
    void expandOccurrences_isCappedForRowsSavedBeforeValidation() {
        var legacy = new Schedule(assignment, Instant.parse("0001-01-01T08:00:00Z"),
                Instant.parse("0001-01-01T09:00:00Z"), RepeatType.DAILY, Instant.parse("9999-12-31T00:00:00Z"));

        assertEquals(Schedule.MAX_OCCURRENCES,
                legacy.expandOccurrences(Instant.parse("9999-12-31T00:00:00Z")).size());
    }

    @Test
    void expandOccurrences_monthly_keepsTheDayOfMonth_noDrift() {
        var jan31 = Instant.parse("2031-01-31T10:00:00Z");
        var monthly = new Schedule(assignment, jan31, jan31.plus(1, ChronoUnit.HOURS), RepeatType.MONTHLY, null);

        var windows = monthly.expandOccurrences(Instant.parse("2031-04-01T00:00:00Z"));

        assertEquals(List.of(jan31, Instant.parse("2031-02-28T10:00:00Z"), Instant.parse("2031-03-31T10:00:00Z")),
                windows.stream().map(Schedule.TimeWindow::start).toList());
    }

    @Test
    void isActiveAt_longRunningSchedule_answersFromTheCurrentOccurrence() {
        var daily = new Schedule(assignment, Instant.parse("2001-01-01T08:00:00Z"),
                Instant.parse("2001-01-01T18:00:00Z"), RepeatType.DAILY, null);

        assertTrue(service.isActiveAt(daily, Instant.parse("2030-06-01T12:00:00Z")));
        assertFalse(service.isActiveAt(daily, Instant.parse("2030-06-01T20:00:00Z")));
    }

    @Test
    void createSchedule_overlapFoundDeepInBothRepeatingSchedules() {
        // Existing: WEEKLY Saturdays 10:00–12:00. New: DAILY 11:00–11:30, starting on a Monday.
        // The first shared slot is the following Saturday — past the first few windows of each.
        var existing = new Schedule(assignment, Instant.parse("2030-06-01T10:00:00Z"),
                Instant.parse("2030-06-01T12:00:00Z"), RepeatType.WEEKLY, null);
        when(scheduleRepository.findByAssignmentIdAndDeletedAtIsNull(anyLong())).thenReturn(List.of(existing));
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createSchedule(assignment, Instant.parse("2030-06-03T11:00:00Z"),
                Instant.parse("2030-06-03T11:30:00Z"), RepeatType.DAILY, Instant.parse("2030-06-20T00:00:00Z"));

        assertEquals(1, result.warnings().size());
        assertEquals(Instant.parse("2030-06-08T11:00:00Z"), result.warnings().getFirst().newWindow().start());
        assertEquals(Instant.parse("2030-06-08T10:00:00Z"), result.warnings().getFirst().existingWindow().start());
    }

    @Test
    void createSchedule_repeatingSchedulesThatNeverMeet_noWarning() {
        var existing = new Schedule(assignment, Instant.parse("2030-06-01T10:00:00Z"),
                Instant.parse("2030-06-01T12:00:00Z"), RepeatType.WEEKLY, null);
        when(scheduleRepository.findByAssignmentIdAndDeletedAtIsNull(anyLong())).thenReturn(List.of(existing));
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createSchedule(assignment, Instant.parse("2030-06-03T13:00:00Z"),
                Instant.parse("2030-06-03T14:00:00Z"), RepeatType.DAILY, Instant.parse("2030-09-01T00:00:00Z"));

        assertFalse(result.hasWarnings());
    }

    @Test
    void createSchedule_repeatingScheduleThatStartedYearsAgo_stillWarnsAboutAFutureOverlap() {
        // New DAILY 10:00–11:00 started three years ago (> MAX_OCCURRENCES days); existing WEEKLY
        // 10:30–11:30 starts next week. Expanding the new one from its start would only ever
        // compare windows that are already over.
        var today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        var newStart = today.minus(3 * 365, ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS);
        var existing = new Schedule(assignment, today.plus(7, ChronoUnit.DAYS).plus(630, ChronoUnit.MINUTES),
                today.plus(7, ChronoUnit.DAYS).plus(690, ChronoUnit.MINUTES), RepeatType.WEEKLY, null);
        when(scheduleRepository.findByAssignmentIdAndDeletedAtIsNull(anyLong())).thenReturn(List.of(existing));
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.createSchedule(assignment, newStart, newStart.plus(1, ChronoUnit.HOURS),
                RepeatType.DAILY, null);

        assertEquals(1, result.warnings().size());
        assertEquals(today.plus(7, ChronoUnit.DAYS).plus(10, ChronoUnit.HOURS),
                result.warnings().getFirst().newWindow().start());
    }
}
