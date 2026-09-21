package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster;
import uz.orientadvertise.services.domain.event.DashboardEventBroadcaster.ContentStatusPayload;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweeper is the safety net that turns "a lost dispatch is permanent" into "a lost dispatch
 * costs a few minutes". These tests pin the three candidate cases, the attempt cap, and — most
 * importantly — that a healthy in-flight encode is left alone.
 */
class TranscodeSweeperTest {

    private static final Instant NOW = Instant.parse("2026-09-06T12:00:00Z");
    private static final Duration LOST_AFTER = Duration.ofMinutes(5);
    private static final Duration LEASE = Duration.ofMinutes(20);
    private static final Duration RETRY_AFTER = Duration.ofMinutes(15);
    private static final Duration STALE_ALERT = Duration.ofMinutes(10);

    private ContentFileRepository repository;
    private TranscodeDispatchService dispatchService;
    private DashboardEventBroadcaster dashboardBroadcaster;
    private TranscodeSweeper sweeper;

    @BeforeEach
    void setUp() {
        repository = mock(ContentFileRepository.class);
        dispatchService = mock(TranscodeDispatchService.class);
        dashboardBroadcaster = mock(DashboardEventBroadcaster.class);
        when(repository.findLostDispatchCandidates(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
        when(repository.findExpiredLeaseCandidates(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
        when(repository.findRetryableFailedCandidates(anyInt(), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());
        sweeper = new TranscodeSweeper(repository, dispatchService, dashboardBroadcaster,
                LOST_AFTER, LEASE, RETRY_AFTER, 3, STALE_ALERT);
    }

    /**
     * Builds a candidate row. ALWAYS assign this to a local before passing it to
     * {@code thenReturn(...)}: creating mocks inside a {@code when(...)} argument trips Mockito's
     * UnfinishedStubbingException.
     */
    private static ContentFile row(long id, ContentFile.Status status, int attempts) {
        var file = mock(ContentFile.class);
        when(file.getId()).thenReturn(id);
        when(file.getStatus()).thenReturn(status);
        when(file.getTranscodeAttempts()).thenReturn(attempts);
        when(file.getStorageKey()).thenReturn("raw/k" + id);
        when(file.getUploadedBy()).thenReturn("alice");
        return file;
    }

    @Test
    void lostDispatch_isReDriven() {
        // The production symptom: a row that has sat in UPLOADED past the grace period.
        var lost = row(1L, ContentFile.Status.UPLOADED, 0);
        when(repository.findLostDispatchCandidates(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(lost));
        when(dispatchService.dispatch(eq(1L), eq(ContentFile.Status.UPLOADED), anyBoolean()))
                .thenReturn(true);

        var result = sweeper.sweep(NOW, false);

        verify(dispatchService).dispatch(1L, ContentFile.Status.UPLOADED, false);
        assertEquals(1, result.redispatched());
    }

    @Test
    void lostDispatchCutoff_isTheGracePeriodBeforeNow() {
        sweeper.sweep(NOW, false);

        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findLostDispatchCandidates(cutoff.capture(), any(Pageable.class));
        assertEquals(NOW.minus(LOST_AFTER), cutoff.getValue());
    }

    @Test
    void healthyInFlightEncode_isNotReclaimed_becauseTheLeaseCutoffIsInThePast() {
        // The core anti-double-dispatch guarantee. The query is only ever handed a cutoff of
        // now - leaseTimeout, so a freshly-leased row is invisible to it. Keying on updated_at
        // instead would surface a merely-slow encode here and start a second ffmpeg on it.
        sweeper.sweep(NOW, false);

        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findExpiredLeaseCandidates(cutoff.capture(), any(Pageable.class));
        assertEquals(NOW.minus(LEASE), cutoff.getValue());
        verify(dispatchService, never()).dispatch(anyLong(), any(), anyBoolean());
    }

    @Test
    void expiredLease_isReclaimedFromTranscoding() {
        var crashed = row(2L, ContentFile.Status.TRANSCODING, 1);
        when(repository.findExpiredLeaseCandidates(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(crashed));
        when(dispatchService.dispatch(eq(2L), eq(ContentFile.Status.TRANSCODING), anyBoolean()))
                .thenReturn(true);

        var result = sweeper.sweep(NOW, false);

        verify(dispatchService).dispatch(2L, ContentFile.Status.TRANSCODING, false);
        assertEquals(1, result.redispatched());
    }

    @Test
    void bootMode_reclaimsEveryTranscodingRowRegardlessOfLeaseAge() {
        // A JVM that has just become ready cannot own an in-flight encode, so waiting out a 20-minute
        // lease after a crash would only delay recovery.
        sweeper.sweep(NOW, true);

        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).findExpiredLeaseCandidates(cutoff.capture(), any(Pageable.class));
        assertEquals(NOW, cutoff.getValue());
    }

    @Test
    void retryableFailure_isRetriedUnderTheCap() {
        var failed = row(3L, ContentFile.Status.FAILED, 1);
        when(repository.findRetryableFailedCandidates(eq(3), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(failed));
        when(dispatchService.dispatch(eq(3L), eq(ContentFile.Status.FAILED), anyBoolean()))
                .thenReturn(true);

        sweeper.sweep(NOW, false);

        verify(dispatchService).dispatch(3L, ContentFile.Status.FAILED, false);
    }

    @Test
    void attemptCapReached_abandonsInsteadOfLooping() {
        // A poison file must not spin forever. It lands in FAILED with a reason a human can read.
        var exhausted = row(4L, ContentFile.Status.UPLOADED, 3);
        when(repository.findLostDispatchCandidates(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(exhausted));
        when(repository.abandonTranscode(eq(4L), eq(ContentFile.Status.UPLOADED), anyString(),
                any(Instant.class))).thenReturn(1);

        var result = sweeper.sweep(NOW, false);

        verify(dispatchService, never()).dispatch(anyLong(), any(), anyBoolean());
        var reason = ArgumentCaptor.forClass(String.class);
        verify(repository).abandonTranscode(eq(4L), eq(ContentFile.Status.UPLOADED), reason.capture(),
                any(Instant.class));
        assertTrue(reason.getValue().contains("3"), "the reason names the attempt count: " + reason.getValue());
        assertEquals(1, result.abandoned());
    }

    @Test
    void rowWithNoRawObject_isAbandonedNotRetried() {
        // Negative: re-dispatching a row with no storage key burns attempts on something that can
        // never succeed, and the worker would just fail on the download.
        var orphan = row(5L, ContentFile.Status.UPLOADED, 0);
        when(orphan.getStorageKey()).thenReturn(null);
        when(repository.findLostDispatchCandidates(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(orphan));
        when(repository.abandonTranscode(anyLong(), any(), anyString(), any(Instant.class))).thenReturn(1);

        sweeper.sweep(NOW, false);

        verify(dispatchService, never()).dispatch(anyLong(), any(), anyBoolean());
        verify(repository).abandonTranscode(eq(5L), eq(ContentFile.Status.UPLOADED),
                org.mockito.ArgumentMatchers.contains("No raw storage key"), any(Instant.class));
    }

    @Test
    void backlog_isLoggedAtWarn_soItReachesTelegram() {
        // WARN+ is forwarded to Telegram by TelegramAppender. This log line IS the alarm that was
        // missing when the incident went unnoticed until a user complained.
        var captured = attachAppender();
        when(repository.countStaleUploaded(any(Instant.class))).thenReturn(2L);

        var result = sweeper.sweep(NOW, false);

        assertEquals(2L, result.backlog());
        boolean warned = captured.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("Transcode backlog: 2 file(s)"));
        assertTrue(warned, "expected a WARN naming the backlog; captured: " + captured.list);
    }

    @Test
    void noBacklog_logsNoAlarm() {
        var captured = attachAppender();
        when(repository.countStaleUploaded(any(Instant.class))).thenReturn(0L);

        sweeper.sweep(NOW, false);

        assertTrue(captured.list.stream()
                        .noneMatch(e -> e.getFormattedMessage().contains("Transcode backlog")),
                "a healthy pipeline must not cry wolf; captured: " + captured.list);
    }

    @Test
    void scheduledSweep_swallowsFailuresSoTheSchedulerKeepsTicking() {
        // A throwing @Scheduled method cancels no future runs in Spring, but the stack trace on the
        // scheduler thread is noise and a transient DB blip must not look like an outage.
        when(repository.findLostDispatchCandidates(any(Instant.class), any(Pageable.class)))
                .thenThrow(new RuntimeException("db unavailable"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> sweeper.scheduledSweep());
    }

    @Test
    void startupRecovery_neverBlocksReadiness() {
        when(repository.findLostDispatchCandidates(any(Instant.class), any(Pageable.class)))
                .thenThrow(new RuntimeException("schema not ready"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> sweeper.recoverOnStartup());
    }

    // ---------- the abandon → live-feed contract (v1.0.134) ----------

    @Test
    void abandoning_announcesTheTerminalFailedOnTheLiveFeed() {
        // This is the ONE terminal transition no operator ever saw: nothing else writes it, so
        // without this broadcast every grid keeps showing "Transcoding" until the next poll.
        var exhausted = row(4L, ContentFile.Status.UPLOADED, 3);
        when(repository.findLostDispatchCandidates(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(exhausted));
        when(repository.abandonTranscode(eq(4L), any(), anyString(), any(Instant.class))).thenReturn(1);
        when(repository.findProjectIdById(4L)).thenReturn(9L);

        sweeper.sweep(NOW, false);

        var payload = ArgumentCaptor.forClass(ContentStatusPayload.class);
        verify(dashboardBroadcaster).contentStatusChanged(payload.capture());
        ContentStatusPayload sent = payload.getValue();
        assertEquals(4L, sent.contentId());
        assertEquals("FAILED", sent.status());
        assertTrue(sent.invalidReason().contains("3"),
                "the frame carries the same reason as the row: " + sent.invalidReason());
        assertEquals(NOW, sent.at());
        // Routing: the file's project AND its uploader — an operator must see their own row fail
        // even when it is orphan content or sits outside their project set.
        assertEquals(9L, sent.projectId());
        assertEquals("alice", sent.uploadedBy());
    }

    @Test
    void abandoningOrphanContent_routesOnTheOwnerAlone() {
        // findProjectIdById returns null for orphan content. The frame must still name its owner,
        // or the operator who uploaded it is the one person who never learns it failed.
        var exhausted = row(6L, ContentFile.Status.UPLOADED, 3);
        when(repository.findLostDispatchCandidates(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(exhausted));
        when(repository.abandonTranscode(eq(6L), any(), anyString(), any(Instant.class))).thenReturn(1);
        when(repository.findProjectIdById(6L)).thenReturn(null);

        sweeper.sweep(NOW, false);

        var payload = ArgumentCaptor.forClass(ContentStatusPayload.class);
        verify(dashboardBroadcaster).contentStatusChanged(payload.capture());
        assertNull(payload.getValue().projectId());
        assertEquals("alice", payload.getValue().uploadedBy());
    }

    @Test
    void casMatchedNoRow_announcesNothing() {
        // Negative, and the important half: a 0 from the CAS means someone else already moved the
        // row. Announcing FAILED anyway would tell every operator something the database does not
        // say — the same "log success before commit" class of bug the pipeline was rewritten to
        // remove.
        var exhausted = row(4L, ContentFile.Status.UPLOADED, 3);
        when(repository.findLostDispatchCandidates(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(exhausted));
        when(repository.abandonTranscode(eq(4L), any(), anyString(), any(Instant.class))).thenReturn(0);

        var result = sweeper.sweep(NOW, false);

        verify(dashboardBroadcaster, never()).contentStatusChanged(any());
        assertEquals(0, result.abandoned());
    }

    @Test
    void redispatchedRow_announcesNothing() {
        // A re-driven row is not terminal — the transcoder broadcasts TRANSCODING itself once it
        // wins the claim. A frame from here would race it with a stale status.
        var lost = row(1L, ContentFile.Status.UPLOADED, 0);
        when(repository.findLostDispatchCandidates(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(lost));
        when(dispatchService.dispatch(eq(1L), any(), anyBoolean())).thenReturn(true);

        sweeper.sweep(NOW, false);

        verify(dashboardBroadcaster, never()).contentStatusChanged(any());
    }

    @Test
    void broadcastFailure_doesNotFailTheSweep() {
        // The live feed is best-effort. A Redis outage must not stop the sweeper from draining a
        // backlog — that would turn a cosmetic outage into a pipeline outage.
        var exhausted = row(4L, ContentFile.Status.UPLOADED, 3);
        when(repository.findLostDispatchCandidates(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(exhausted));
        when(repository.abandonTranscode(eq(4L), any(), anyString(), any(Instant.class))).thenReturn(1);
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(dashboardBroadcaster).contentStatusChanged(any());

        var result = assertDoesNotThrow(() -> sweeper.sweep(NOW, false));

        assertEquals(1, result.abandoned());
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(TranscodeSweeper.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
