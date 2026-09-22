package uz.orientadvertise.services.service;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import uz.orientadvertise.services.domain.repository.EventRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;
import uz.orientadvertise.services.infra.audit.AuditLogRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionCleanupServiceTest {

    private EventRepository eventRepository;
    private PlaybackLogRepository playbackLogRepository;
    private AuditLogRepository auditLogRepository;
    private RetentionProperties properties;
    private RetentionCleanupService service;

    @BeforeEach
    void setUp() throws Exception {
        eventRepository = mock(EventRepository.class);
        playbackLogRepository = mock(PlaybackLogRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        properties = new RetentionProperties();
        properties.setGuardWindow(false);     // the window itself is covered by its own tests
        // Self-reference for the @Transactional(REQUIRES_NEW) batch indirection.
        // For unit tests we just point at the same instance — the transactional behavior
        // isn't under test, only the orchestration / counts / guards.
        service = new RetentionCleanupService(eventRepository, playbackLogRepository,
                auditLogRepository, properties, null);
        Field selfField = RetentionCleanupService.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(service, service);

        when(eventRepository.findExpiredIdsNotReferencedByIncidents(any(), any()))
                .thenReturn(List.of());
        when(playbackLogRepository.findIdsOlderThan(any(), any()))
                .thenReturn(List.of());
        when(auditLogRepository.findIdsOlderThan(any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void run_outsideMaintenanceWindow_skipsAndLogs() {
        properties.setGuardWindow(true);
        // Test runs whenever; if the host clock happens to be inside the 1-4 AM Asia/Karachi
        // window, the guard would *allow* — so we deterministically check the helper.
        // The orchestration tests use the guard disabled.
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
        assertEquals(0, result.auditLogsDeleted());
        assertFalse(result.skipped());
        verify(eventRepository, never()).deleteAllByIdInBatch(anyList());
    }

    @Test
    void runCleanup_singleBatchEachTable() {
        when(eventRepository.findExpiredIdsNotReferencedByIncidents(any(), any()))
                .thenReturn(List.of(1L, 2L, 3L))
                .thenReturn(List.of());
        when(playbackLogRepository.findIdsOlderThan(any(), any()))
                .thenReturn(List.of(10L, 11L))
                .thenReturn(List.of());
        when(auditLogRepository.findIdsOlderThan(any(), any()))
                .thenReturn(List.of(20L))
                .thenReturn(List.of());

        var result = service.runCleanup();

        assertEquals(3, result.eventsDeleted());
        assertEquals(2, result.playbackLogsDeleted());
        assertEquals(1, result.auditLogsDeleted());
        verify(eventRepository).deleteAllByIdInBatch(List.of(1L, 2L, 3L));
        verify(playbackLogRepository).deleteAllByIdInBatch(List.of(10L, 11L));
        verify(auditLogRepository).deleteAllByIdInBatch(List.of(20L));
    }

    @Test
    void runCleanup_drainsMultipleBatches_untilSmallBatchSeen() {
        // 2 full batches of 1000 + a partial batch of 250 = 2250 rows
        List<Long> full1 = ids(1, 1000);
        List<Long> full2 = ids(1001, 2000);
        List<Long> partial = ids(2001, 2250);
        when(eventRepository.findExpiredIdsNotReferencedByIncidents(any(), any()))
                .thenReturn(full1)
                .thenReturn(full2)
                .thenReturn(partial)
                .thenReturn(List.of());

        var result = service.runCleanup();

        assertEquals(2250, result.eventsDeleted());
        // Drains until size < batchSize → 3 calls (full, full, partial), no 4th.
        verify(eventRepository, times(3)).findExpiredIdsNotReferencedByIncidents(any(), any());
        verify(eventRepository, times(3)).deleteAllByIdInBatch(anyList());
    }

    // ---------- DATA-01: per-table windows (v1.0.143) ----------

    /**
     * The three tables no longer share one threshold. audit_log is swept at 14 days because
     * nothing reads it and device traffic is no longer written to it; playback_log is
     * proof-of-play and stays at 90. Captured per repository, so collapsing them back onto one
     * shared threshold fails here.
     */
    @Test
    void eachTable_drainsAgainstItsOwnThreshold() {
        properties.setAudit(Duration.ofDays(14));
        properties.setPlayback(Duration.ofDays(90));
        properties.setEvent(Duration.ofDays(90));
        Instant before = Instant.now();

        service.runCleanup();

        Instant after = Instant.now();
        assertThresholdIsAgo(captureEventThreshold(), Duration.ofDays(90), before, after, "event");
        assertThresholdIsAgo(capturePlaybackThreshold(), Duration.ofDays(90), before, after, "playback_log");
        assertThresholdIsAgo(captureAuditThreshold(), Duration.ofDays(14), before, after, "audit_log");
    }

    @Test
    void aShorterConfiguredAuditWindow_movesOnlyTheAuditThreshold() {
        properties.setAudit(Duration.ofDays(3));
        Instant before = Instant.now();

        service.runCleanup();

        Instant after = Instant.now();
        assertThresholdIsAgo(captureAuditThreshold(), Duration.ofDays(3), before, after, "audit_log");
        assertThresholdIsAgo(capturePlaybackThreshold(), Duration.ofDays(90), before, after, "playback_log");
    }

    /**
     * The old fixed cap was 100 batches × 1000 rows per table. Past roughly 26 always-on devices
     * playback_log grew faster than that, so the table could never shrink again. Draining past
     * 100 batches is the whole point of the new safety stop.
     */
    @Test
    @Timeout(30)
    void runCleanup_drainsFarMoreThanTheOldHundredBatchCap() {
        properties.setBatchSize(1);                 // 1 row per batch keeps the test cheap
        properties.setMaxBatchesPerRun(5000);
        var remaining = new AtomicInteger(150);     // > the old cap of 100
        when(eventRepository.findExpiredIdsNotReferencedByIncidents(any(), any()))
                .thenAnswer(inv -> remaining.getAndDecrement() > 0 ? List.of(1L) : List.of());

        var result = service.runCleanup();

        assertEquals(150, result.eventsDeleted(), "the drain must not stop at 100 batches");
        verify(eventRepository, times(151)).findExpiredIdsNotReferencedByIncidents(any(), any());
    }

    /**
     * A repository that always reports a full batch would loop forever without a stop. The run's
     * wall-clock budget ends it — and it is checked AFTER a batch, so every table still makes
     * progress. maxBatchesPerRun is set low here so a broken budget check fails the assertion
     * instead of hanging the suite.
     */
    @Test
    @Timeout(30)
    void runCleanup_stopsOnTheRunBudget_andSaysSoAtInfo() {
        properties.setBatchSize(2);
        // Set on the bean, not bound: RetentionProperties validation refuses anything under a
        // minute, so a zero budget is only reachable here — which is what makes the stop assertable
        // without the test waiting a minute.
        properties.setMaxRunDuration(Duration.ZERO);
        properties.setMaxBatchesPerRun(5);          // a broken budget check ⇒ 5 batches, not 1
        when(eventRepository.findExpiredIdsNotReferencedByIncidents(any(), any()))
                .thenReturn(List.of(1L, 2L));
        var appender = attachAppender();

        var result = service.runCleanup();

        assertEquals(2, result.eventsDeleted(), "exactly one batch fits in a zero budget");
        verify(eventRepository, times(1)).findExpiredIdsNotReferencedByIncidents(any(), any());
        // The backlog must be visible, and at INFO: an unfinished table is routine, and WARN
        // reaches Telegram.
        var stop = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("ran out of time on events"))
                .findFirst().orElseThrow(() -> new AssertionError("no budget-stop log line"));
        assertEquals(Level.INFO, stop.getLevel());
        assertTrue(stop.getFormattedMessage().contains("2 rows"),
                "the log must name the rows deleted, was: " + stop.getFormattedMessage());
    }

    @Test
    @Timeout(30)
    void runCleanup_stopsOnTheBatchSafetyCap_andSaysSoAtInfo() {
        properties.setBatchSize(2);
        properties.setMaxBatchesPerRun(3);
        properties.setMaxRunDuration(Duration.ofHours(1));   // budget can't be what stops it
        when(playbackLogRepository.findIdsOlderThan(any(), any())).thenReturn(List.of(1L, 2L));
        var appender = attachAppender();

        var result = service.runCleanup();

        assertEquals(6, result.playbackLogsDeleted());
        verify(playbackLogRepository, times(3)).findIdsOlderThan(any(), any());
        var stop = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("safety stop on playback_logs"))
                .findFirst().orElseThrow(() -> new AssertionError("no cap-stop log line"));
        assertEquals(Level.INFO, stop.getLevel());
        assertTrue(stop.getFormattedMessage().contains("6 rows"), stop.getFormattedMessage());
    }

    @Test
    void runCleanup_eventBatchFailure_doesNotPreventPlaybackCleanup() {
        when(eventRepository.findExpiredIdsNotReferencedByIncidents(any(), any()))
                .thenThrow(new RuntimeException("DB hiccup on first event batch"));
        when(playbackLogRepository.findIdsOlderThan(any(), any()))
                .thenReturn(List.of(99L))
                .thenReturn(List.of());
        when(auditLogRepository.findIdsOlderThan(any(), any()))
                .thenReturn(List.of(7L))
                .thenReturn(List.of());

        var result = service.runCleanup();

        assertEquals(0, result.eventsDeleted());
        // The other tables run independently — not blocked by the event failure.
        assertEquals(1, result.playbackLogsDeleted());
        assertEquals(1, result.auditLogsDeleted());
    }

    @Test
    void deleteExpiredEventBatch_emptyList_returnsZeroAndDoesNotCallDelete() {
        when(eventRepository.findExpiredIdsNotReferencedByIncidents(any(), any()))
                .thenReturn(List.of());
        int deleted = service.deleteExpiredEventBatch(Instant.now());
        assertEquals(0, deleted);
        verify(eventRepository, never()).deleteAllByIdInBatch(anyList());
    }

    @Test
    void deleteExpiredEventBatch_usesTheConfiguredBatchSize() {
        properties.setBatchSize(250);

        service.deleteExpiredEventBatch(Instant.now());

        verify(eventRepository).findExpiredIdsNotReferencedByIncidents(any(),
                eq(PageRequest.of(0, 250)));
    }

    private static List<Long> ids(int start, int endInclusive) {
        var out = new ArrayList<Long>();
        for (long i = start; i <= endInclusive; i++) {
            out.add(i);
        }
        return out;
    }

    private Instant captureEventThreshold() {
        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(eventRepository, atLeast(1)).findExpiredIdsNotReferencedByIncidents(captor.capture(), any());
        return captor.getValue();
    }

    private Instant capturePlaybackThreshold() {
        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(playbackLogRepository, atLeast(1)).findIdsOlderThan(captor.capture(), any());
        return captor.getValue();
    }

    private Instant captureAuditThreshold() {
        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(auditLogRepository, atLeast(1)).findIdsOlderThan(captor.capture(), any());
        return captor.getValue();
    }

    /** The threshold must sit inside [before-age, after-age] — no slack beyond the test's own runtime. */
    private static void assertThresholdIsAgo(Instant threshold, Duration age,
                                             Instant before, Instant after, String table) {
        assertFalse(threshold.isBefore(before.minus(age)),
                table + " threshold is older than " + age + ": " + threshold);
        assertFalse(threshold.isAfter(after.minus(age)),
                table + " threshold is newer than " + age + ": " + threshold);
    }

    private final List<ListAppender<ILoggingEvent>> attached = new ArrayList<>();

    private ListAppender<ILoggingEvent> attachAppender() {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        SERVICE_LOGGER.addAppender(appender);
        attached.add(appender);
        return appender;
    }

    /** Logback loggers are shared and static — an appender left on one leaks into every later test. */
    @AfterEach
    void detachAppenders() {
        attached.forEach(a -> {
            SERVICE_LOGGER.detachAppender(a);
            a.stop();
        });
        attached.clear();
    }

    private static final Logger SERVICE_LOGGER =
            (Logger) LoggerFactory.getLogger(RetentionCleanupService.class);

    // ---------- audit_log (v1.0.133) ----------

    @Test
    void runCleanup_drainsAuditLogToo() {
        // audit_log was pruned by NOTHING before v1.0.133, and every row carries a full HTTP
        // request AND response body, on the same volume as the Postgres data directory and MinIO.
        when(auditLogRepository.findIdsOlderThan(any(), any())).thenReturn(List.of(1L, 2L, 3L));

        var result = service.runCleanup();

        assertEquals(3, result.auditLogsDeleted());
        verify(auditLogRepository).deleteAllByIdInBatch(List.of(1L, 2L, 3L));
    }

    @Test
    void deleteExpiredAuditBatch_emptyList_returnsZeroAndDoesNotCallDelete() {
        when(auditLogRepository.findIdsOlderThan(any(), any())).thenReturn(List.of());

        int deleted = service.deleteExpiredAuditBatch(Instant.now());

        assertEquals(0, deleted);
        verify(auditLogRepository, never()).deleteAllByIdInBatch(anyList());
    }

    @Test
    void deleteExpiredAuditBatch_usesTheConfiguredBatchSize() {
        properties.setBatchSize(137);            // NOT the default, or this can't fail for its reason
        when(auditLogRepository.findIdsOlderThan(any(), any())).thenReturn(List.of());

        service.deleteExpiredAuditBatch(Instant.now());

        verify(auditLogRepository).findIdsOlderThan(any(), eq(PageRequest.of(0, 137)));
    }

    @Test
    void auditDrainFailure_doesNotAbortTheRun() {
        // A failure draining one table must not cost the deletes already committed for the others —
        // each batch runs in its own transaction precisely so partial progress sticks.
        when(eventRepository.findExpiredIdsNotReferencedByIncidents(any(), any()))
                .thenReturn(List.of(9L));
        when(auditLogRepository.findIdsOlderThan(any(), any()))
                .thenThrow(new RuntimeException("db blip"));

        var result = service.runCleanup();

        assertEquals(1, result.eventsDeleted(), "events still drained despite the audit failure");
        assertEquals(0, result.auditLogsDeleted());
        assertFalse(result.skipped());
    }

    @Test
    void skippedRun_touchesNoTableAndReportsZeroForAllThree() {
        properties.setGuardWindow(true);
        when(eventRepository.findExpiredIdsNotReferencedByIncidents(any(), any()))
                .thenReturn(List.of(1L));        // would be deleted if the guard let the run through
        Instant noonUtc = ZonedDateTime.of(2026, 5, 6, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant();

        // Driven from a fixed instant: 5pm Asia/Karachi is outside the window whatever time the
        // suite itself runs at.
        var result = service.runCleanup(noonUtc);

        assertTrue(result.skipped());
        assertEquals(0, result.eventsDeleted());
        assertEquals(0, result.playbackLogsDeleted());
        assertEquals(0, result.auditLogsDeleted());
        verify(eventRepository, never()).findExpiredIdsNotReferencedByIncidents(any(), any());
        verify(playbackLogRepository, never()).findIdsOlderThan(any(), any());
        verify(auditLogRepository, never()).findIdsOlderThan(any(), any());
    }

    @Test
    void insideTheWindow_theGuardLetsTheRunThrough() {
        properties.setGuardWindow(true);
        when(auditLogRepository.findIdsOlderThan(any(), any())).thenReturn(List.of(5L));
        Instant twoAmKarachi = ZonedDateTime.of(2026, 5, 6, 2, 0, 0, 0, ZoneId.of("Asia/Karachi"))
                .toInstant();

        var result = service.runCleanup(twoAmKarachi);

        assertFalse(result.skipped());
        assertEquals(1, result.auditLogsDeleted());
    }
}
