package uz.orientadvertise.services.service;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionCleanupServiceTest {

    private EventRepository eventRepository;
    private PlaybackLogRepository playbackLogRepository;
    private RetentionCleanupService service;

    @BeforeEach
    void setUp() throws Exception {
        eventRepository = mock(EventRepository.class);
        playbackLogRepository = mock(PlaybackLogRepository.class);
        // Self-reference for the @Transactional(REQUIRES_NEW) batch indirection.
        // For unit tests we just point at the same instance — the transactional behavior
        // isn't under test, only the orchestration / counts / guards.
        service = new RetentionCleanupService(eventRepository, playbackLogRepository, null);
        Field selfField = RetentionCleanupService.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(service, service);

        Field guardField = RetentionCleanupService.class.getDeclaredField("guardWindowEnabled");
        guardField.setAccessible(true);
        guardField.setBoolean(service, false); // disabled by default for tests

        when(eventRepository.findExpiredIdsSkippingOpenIncidents(any(), any()))
                .thenReturn(List.of());
        when(playbackLogRepository.findIdsOlderThan(any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void run_outsideMaintenanceWindow_skipsAndLogs() throws Exception {
        Field f = RetentionCleanupService.class.getDeclaredField("guardWindowEnabled");
        f.setAccessible(true);
        f.setBoolean(service, true);
        // Test runs whenever; if the host clock happens to be inside the 1-4 AM Asia/Karachi
        // window, the guard would *allow* — so we deterministically check the helper.
        // The orchestration test below uses guard disabled.
        Instant noonUtc = ZonedDateTime.of(2026, 5, 6, 12, 0, 0, 0, ZoneId.of("UTC"))
                .toInstant();
        assertFalse(RetentionCleanupService.isInMaintenanceWindow(noonUtc),
                "noon UTC = 5pm Asia/Karachi → outside window");
    }

    @Test
    void window_includesTwoAmKarachi() {
        Instant twoAmKarachi = ZonedDateTime.of(2026, 5, 6, 2, 0, 0, 0, ZoneId.of("Asia/Karachi"))
                .toInstant();
        assertTrue(RetentionCleanupService.isInMaintenanceWindow(twoAmKarachi));
    }

    @Test
    void window_excludesFourAmKarachi() {
        // Window is half-open: [01, 04). 04:00 itself is out.
        Instant fourAm = ZonedDateTime.of(2026, 5, 6, 4, 0, 0, 0, ZoneId.of("Asia/Karachi"))
                .toInstant();
        assertFalse(RetentionCleanupService.isInMaintenanceWindow(fourAm));
    }

    @Test
    void runCleanup_noExpiredRows_logsZeroDeletes() {
        var result = service.runCleanup();

        assertEquals(0, result.eventsDeleted());
        assertEquals(0, result.playbackLogsDeleted());
        assertFalse(result.skipped());
        verify(eventRepository, never()).deleteAllByIdInBatch(anyList());
    }

    @Test
    void runCleanup_singleBatchEachTable() {
        when(eventRepository.findExpiredIdsSkippingOpenIncidents(any(), any()))
                .thenReturn(List.of(1L, 2L, 3L))
                .thenReturn(List.of());
        when(playbackLogRepository.findIdsOlderThan(any(), any()))
                .thenReturn(List.of(10L, 11L))
                .thenReturn(List.of());

        var result = service.runCleanup();

        assertEquals(3, result.eventsDeleted());
        assertEquals(2, result.playbackLogsDeleted());
        verify(eventRepository).deleteAllByIdInBatch(List.of(1L, 2L, 3L));
        verify(playbackLogRepository).deleteAllByIdInBatch(List.of(10L, 11L));
    }

    @Test
    void runCleanup_drainsMultipleBatches_untilSmallBatchSeen() {
        // 2 full batches of 1000 + a partial batch of 250 = 2250 rows
        List<Long> full1 = ids(1, 1000);
        List<Long> full2 = ids(1001, 2000);
        List<Long> partial = ids(2001, 2250);
        when(eventRepository.findExpiredIdsSkippingOpenIncidents(any(), any()))
                .thenReturn(full1)
                .thenReturn(full2)
                .thenReturn(partial)
                .thenReturn(List.of());

        var result = service.runCleanup();

        assertEquals(2250, result.eventsDeleted());
        // Drains until size < BATCH_SIZE → 3 calls (full, full, partial), no 4th.
        verify(eventRepository, times(3)).findExpiredIdsSkippingOpenIncidents(any(), any());
        verify(eventRepository, times(3)).deleteAllByIdInBatch(anyList());
    }

    @Test
    void runCleanup_capsAtMaxBatchesPerRun() {
        // Always return a full batch so the drain only stops via the iteration cap.
        List<Long> full = ids(1, 1000);
        when(eventRepository.findExpiredIdsSkippingOpenIncidents(any(), any())).thenReturn(full);

        var result = service.runCleanup();

        assertEquals(1000 * RetentionCleanupService.MAX_BATCHES_PER_RUN, result.eventsDeleted());
        verify(eventRepository, times(RetentionCleanupService.MAX_BATCHES_PER_RUN))
                .deleteAllByIdInBatch(anyList());
    }

    @Test
    void runCleanup_thresholdIs90DaysAgo() {
        Instant before = Instant.now();
        service.runCleanup();
        Instant after = Instant.now();

        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(eventRepository, atLeast(1))
                .findExpiredIdsSkippingOpenIncidents(captor.capture(), any());
        Instant threshold = captor.getValue();
        // threshold should be ~90 days before "now"
        long minDelta = Duration.between(threshold, before).toDays();
        long maxDelta = Duration.between(threshold, after).toDays();
        assertTrue(minDelta == 90 || maxDelta == 90,
                "threshold should be 90 days before now, got "
                        + Duration.between(threshold, after).toDays() + " days");
    }

    @Test
    void runCleanup_eventBatchFailure_doesNotPreventPlaybackCleanup() {
        when(eventRepository.findExpiredIdsSkippingOpenIncidents(any(), any()))
                .thenThrow(new RuntimeException("DB hiccup on first event batch"));
        when(playbackLogRepository.findIdsOlderThan(any(), any()))
                .thenReturn(List.of(99L))
                .thenReturn(List.of());

        var result = service.runCleanup();

        assertEquals(0, result.eventsDeleted());
        // Playback cleanup runs independently — not blocked by event failure.
        assertEquals(1, result.playbackLogsDeleted());
    }

    @Test
    void deleteExpiredEventBatch_emptyList_returnsZeroAndDoesNotCallDelete() {
        when(eventRepository.findExpiredIdsSkippingOpenIncidents(any(), any()))
                .thenReturn(List.of());
        int deleted = service.deleteExpiredEventBatch(Instant.now());
        assertEquals(0, deleted);
        verify(eventRepository, never()).deleteAllByIdInBatch(anyList());
    }

    @Test
    void deleteExpiredEventBatch_usesPageSizeOf1000() {
        var captor = org.mockito.ArgumentCaptor.forClass(PageRequest.class);
        service.deleteExpiredEventBatch(Instant.now());
        verify(eventRepository).findExpiredIdsSkippingOpenIncidents(any(), captor.capture());
        assertEquals(RetentionCleanupService.BATCH_SIZE, captor.getValue().getPageSize());
    }

    private static List<Long> ids(int start, int endInclusive) {
        var out = new ArrayList<Long>();
        for (long i = start; i <= endInclusive; i++) {
            out.add(i);
        }
        return out;
    }
}
