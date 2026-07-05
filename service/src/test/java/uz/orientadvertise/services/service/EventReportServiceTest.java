package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.domain.repository.IncidentRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventReportServiceTest {

    private EventRepository eventRepository;
    private IncidentRepository incidentRepository;
    private EventReportService self;
    private EventReportService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        incidentRepository = mock(IncidentRepository.class);
        self = mock(EventReportService.class);
        service = new EventReportService(eventRepository, incidentRepository, self);

        when(eventRepository.countByTypeInRange(any(), any(), any())).thenReturn(List.of());
        when(eventRepository.topAffectedDevices(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(incidentRepository.countOpenedInRange(any(), any(), any())).thenReturn(0L);
        when(incidentRepository.avgResolutionSeconds(any(), any(), any())).thenReturn(null);
    }

    @Test
    void noEventsInRange_returnsZeroFilledStructure() {
        when(eventRepository.countInRange(eq(1L), any(), any())).thenReturn(0L);

        var outcome = service.run(1L, Instant.now().minus(Duration.ofDays(7)), Instant.now(), null);

        assertFalse(outcome.async(), "small range should run sync");
        var report = outcome.report();
        assertEquals(0, report.totalEvents());
        assertEquals(0, report.incidentCount());
        assertNull(report.avgResolutionSeconds(), "no resolved incidents → null, not 0");
        assertTrue(report.countsByType().isEmpty());
        assertTrue(report.topAffectedDevices().isEmpty());
    }

    @Test
    void avgResolution_excludesUnresolvedIncidents_byPropagatingNullFromRepo() {
        // Repo only counts RESOLVED incidents in its native AVG. When all incidents are
        // unresolved, the repo returns null — we surface that as null (not 0).
        when(eventRepository.countInRange(any(), any(), any())).thenReturn(50L);
        when(incidentRepository.countOpenedInRange(any(), any(), any())).thenReturn(5L);
        when(incidentRepository.avgResolutionSeconds(any(), any(), any())).thenReturn(null);

        var outcome = service.run(1L, Instant.now().minus(Duration.ofDays(7)), Instant.now(), null);

        assertFalse(outcome.async());
        assertEquals(5, outcome.report().incidentCount());
        assertNull(outcome.report().avgResolutionSeconds());
    }

    @Test
    void avgResolution_returnsRepoValue_whenSomeResolved() {
        when(eventRepository.countInRange(any(), any(), any())).thenReturn(50L);
        when(incidentRepository.countOpenedInRange(any(), any(), any())).thenReturn(5L);
        when(incidentRepository.avgResolutionSeconds(any(), any(), any())).thenReturn(3600.0);

        var outcome = service.run(1L, Instant.now().minus(Duration.ofDays(7)), Instant.now(), null);

        assertEquals(3600.0, outcome.report().avgResolutionSeconds());
    }

    @Test
    void countsByType_aggregatedFromObjectArrays() {
        when(eventRepository.countInRange(any(), any(), any())).thenReturn(15L);
        when(eventRepository.countByTypeInRange(any(), any(), any())).thenReturn(List.of(
                new Object[]{"OFFLINE", 10L},
                new Object[]{"SYNC_TIMEOUT", 5L}));

        var report = service.run(1L, Instant.now().minus(Duration.ofDays(1)), Instant.now(), null).report();

        assertEquals(2, report.countsByType().size());
        assertEquals(10L, report.countsByType().get("OFFLINE"));
        assertEquals(5L, report.countsByType().get("SYNC_TIMEOUT"));
    }

    @Test
    void topAffectedDevices_aggregatedFromObjectArrays() {
        when(eventRepository.countInRange(any(), any(), any())).thenReturn(15L);
        when(eventRepository.topAffectedDevices(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(
                        new Object[]{1L, "TV-1", 10L},
                        new Object[]{2L, "TV-2", 5L}));

        var report = service.run(null, Instant.now().minus(Duration.ofDays(1)), Instant.now(), null).report();

        assertEquals(2, report.topAffectedDevices().size());
        assertEquals(1L, report.topAffectedDevices().get(0).deviceId());
        assertEquals("TV-1", report.topAffectedDevices().get(0).deviceName());
        assertEquals(10L, report.topAffectedDevices().get(0).eventCount());
    }

    @Test
    void aboveThreshold_runsAsync_andReturnsJobId() {
        when(eventRepository.countInRange(any(), any(), any()))
                .thenReturn(EventReportService.ASYNC_THRESHOLD + 1);

        var outcome = service.run(1L, Instant.now().minus(Duration.ofDays(60)), Instant.now(), null);

        assertTrue(outcome.async(), "above threshold must dispatch to async");
        assertNotNull(outcome.jobId());
        assertNull(outcome.report());
        verify(self, times(1)).submitAsync(eq(outcome.jobId()), eq(1L), any(), any(), anyLong(), any());
    }

    @Test
    void atThreshold_runsSync() {
        // Boundary: exactly ASYNC_THRESHOLD events stays sync (the rule is "above").
        when(eventRepository.countInRange(any(), any(), any()))
                .thenReturn(EventReportService.ASYNC_THRESHOLD);

        var outcome = service.run(1L, Instant.now().minus(Duration.ofDays(7)), Instant.now(), null);

        assertFalse(outcome.async());
        verify(self, never()).submitAsync(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void asyncJob_pendingAfterDispatch_completedWhenSubmitAsyncFinishes() {
        // Verify the PENDING → COMPLETED transition by intercepting the self.submitAsync
        // call: we look up the queued job via getJob (which throws if missing) and confirm
        // its status, then run the real computation by delegating to the actual service.
        when(eventRepository.countInRange(any(), any(), any()))
                .thenReturn(EventReportService.ASYNC_THRESHOLD + 1);

        doAnswer(inv -> {
            String jobId = inv.getArgument(0);
            // Job is recorded as PENDING immediately when service.run dispatches.
            var pending = service.getJob(jobId);
            assertEquals(EventReportService.JobStatus.PENDING, pending.status());
            // Now run the real computation path so the job flips to COMPLETED.
            service.submitAsync(jobId, inv.getArgument(1), inv.getArgument(2),
                    inv.getArgument(3), inv.getArgument(4), inv.getArgument(5));
            return null;
        }).when(self).submitAsync(any(), any(), any(), any(), anyLong(), any());

        var outcome = service.run(1L, Instant.now().minus(Duration.ofDays(60)), Instant.now(), null);

        var finished = service.getJob(outcome.jobId());
        assertEquals(EventReportService.JobStatus.COMPLETED, finished.status());
        assertNotNull(finished.result());
    }

    @Test
    void asyncJob_failurePathRecordsErrorMessage() {
        when(eventRepository.countInRange(any(), any(), any()))
                .thenReturn(EventReportService.ASYNC_THRESHOLD + 1);
        when(eventRepository.countByTypeInRange(any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        doAnswer(inv -> {
            service.submitAsync(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                    inv.getArgument(3), inv.getArgument(4), inv.getArgument(5));
            return null;
        }).when(self).submitAsync(any(), any(), any(), any(), anyLong(), any());

        var outcome = service.run(1L, Instant.now().minus(Duration.ofDays(60)), Instant.now(), null);

        var job = service.getJob(outcome.jobId());
        assertEquals(EventReportService.JobStatus.FAILED, job.status());
        assertNotNull(job.error());
        assertTrue(job.error().contains("boom"));
    }

    @Test
    void getJob_unknownId_throws404() {
        assertThrows(ResourceNotFoundException.class, () -> service.getJob("nope"));
    }

    @Test
    void rangeOver90Days_throws400() {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(91));
        assertThrows(IllegalArgumentException.class, () -> service.run(1L, from, to, null));
    }

    @Test
    void fromAfterTo_throws400() {
        Instant to = Instant.now().minus(Duration.ofDays(1));
        Instant from = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> service.run(1L, from, to, null));
    }

    @Test
    void noBounds_defaultsTo30DayWindow() {
        when(eventRepository.countInRange(any(), any(), any())).thenReturn(0L);
        var outcome = service.run(1L, null, null, null);
        assertFalse(outcome.async());
        long days = Duration.between(outcome.report().from(), outcome.report().to()).toDays();
        assertEquals(30, days);
    }
}
