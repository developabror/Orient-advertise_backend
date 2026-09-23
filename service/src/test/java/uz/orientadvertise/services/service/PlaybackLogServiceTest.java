package uz.orientadvertise.services.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.repository.ContentAssignmentExclusionRepository;
import uz.orientadvertise.services.domain.repository.ContentAssignmentRepository;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.service.PlaybackLogService.PlaybackLogResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaybackLogServiceTest {

    private PlaybackLogRepository repository;
    private DeviceRepository deviceRepository;
    private ContentFileRepository contentFileRepository;
    private PlaybackLogService service;
    private Device device;
    private ContentFile contentFile;

    @BeforeEach
    void setUp() {
        repository = mock(PlaybackLogRepository.class);
        deviceRepository = mock(DeviceRepository.class);
        contentFileRepository = mock(ContentFileRepository.class);
        service = serviceWith(new RetentionProperties());    // playback defaults to 90 days
        device = mock(Device.class);
        when(device.getId()).thenReturn(1L);
        contentFile = mock(ContentFile.class);
        when(contentFile.getId()).thenReturn(10L);
    }

    /** resolveForDevice defaults to null → no assignment → the batch assignment-check is skipped. */
    private PlaybackLogService serviceWith(RetentionProperties retention) {
        return new PlaybackLogService(repository, deviceRepository, contentFileRepository,
                mock(ContentAssignmentRepository.class), mock(ContentAssignmentExclusionRepository.class),
                mock(PlaylistItemRepository.class), retention, 30, Duration.ofMinutes(30));
    }

    /**
     * {@code insertIgnoringDuplicate} returns a primitive {@code int}, so an unstubbed mock
     * returns 0 — which the service correctly reads as "duplicate". Any test asserting
     * {@code Created} MUST stub it to 1 explicitly or it passes for the wrong reason.
     */
    private void stubInsertReturns(int affected) {
        when(repository.insertIgnoringDuplicate(anyLong(), anyLong(), nullable(Long.class),
                any(Instant.class), nullable(Integer.class), any(Instant.class)))
                .thenReturn(affected);
    }

    private void verifyNoInsertAttempted() {
        verify(repository, never()).insertIgnoringDuplicate(anyLong(), anyLong(),
                nullable(Long.class), any(Instant.class), nullable(Integer.class), any(Instant.class));
    }

    @Test
    void record_validPlayedAt_createsEntry() {
        var playedAt = Instant.now().minus(5, ChronoUnit.MINUTES);
        stubInsertReturns(1);

        var result = service.record(device, contentFile, null, playedAt, 30);

        assertInstanceOf(PlaybackLogResult.Created.class, result);
        verify(repository).insertIgnoringDuplicate(eq(1L), eq(10L), nullable(Long.class),
                eq(playedAt), eq(30), any(Instant.class));
    }

    @Test
    void record_playedAtInFutureBeyondSkew_rejected() {
        var playedAt = Instant.now().plus(2, ChronoUnit.MINUTES); // 120s > 30s tolerance

        var result = service.record(device, contentFile, null, playedAt, 30);

        assertInstanceOf(PlaybackLogResult.Rejected.class, result);
        verifyNoInsertAttempted();
    }

    @Test
    void record_playedAtSlightlyInFutureWithinSkew_accepted() {
        var playedAt = Instant.now().plus(15, ChronoUnit.SECONDS); // 15s < 30s tolerance
        stubInsertReturns(1);

        var result = service.record(device, contentFile, null, playedAt, 30);

        assertInstanceOf(PlaybackLogResult.Created.class, result);
    }

    @Test
    void record_exactlyAtSkewBoundary_accepted() {
        var playedAt = Instant.now().plus(29, ChronoUnit.SECONDS); // 29s < 30s
        stubInsertReturns(1);

        var result = service.record(device, contentFile, null, playedAt, 30);

        assertInstanceOf(PlaybackLogResult.Created.class, result);
    }

    @Test
    void record_duplicate_returnsDuplicateResult() {
        // 0 rows affected = the database skipped an existing (device, content, played_at) via
        // ON CONFLICT DO NOTHING. No exception is raised, so the caller's transaction survives.
        var playedAt = Instant.now().minus(1, ChronoUnit.MINUTES);
        stubInsertReturns(0);

        var result = service.record(device, contentFile, null, playedAt, 30);

        assertInstanceOf(PlaybackLogResult.Duplicate.class, result);
    }

    @Test
    void record_nonDuplicateIntegrityViolation_rethrows() {
        // ON CONFLICT DO NOTHING covers unique/exclusion conflicts only. A foreign-key violation
        // is a genuine defect and must still propagate rather than be swallowed as a duplicate.
        var playedAt = Instant.now().minus(1, ChronoUnit.MINUTES);
        when(repository.insertIgnoringDuplicate(anyLong(), anyLong(), nullable(Long.class),
                any(Instant.class), nullable(Integer.class), any(Instant.class)))
                .thenThrow(new DataIntegrityViolationException("fk_playback_device"));

        org.junit.jupiter.api.Assertions.assertThrows(DataIntegrityViolationException.class, () ->
                service.record(device, contentFile, null, playedAt, 30));
    }

    @Test
    void record_playedAtInPast_accepted() {
        var playedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        stubInsertReturns(1);

        var result = service.record(device, contentFile, null, playedAt, 60);

        assertInstanceOf(PlaybackLogResult.Created.class, result);
    }

    @Test
    void record_withAssignment_passesAssignmentIdToTheInsert() {
        // Created no longer carries an entity — nothing is materialized on the write path — so
        // the assignment binding is asserted at the call instead.
        var assignment = mock(ContentAssignment.class);
        when(assignment.getId()).thenReturn(7L);
        var playedAt = Instant.now().minus(10, ChronoUnit.SECONDS);
        stubInsertReturns(1);

        var result = service.record(device, contentFile, assignment, playedAt, 45);

        assertInstanceOf(PlaybackLogResult.Created.class, result);
        verify(repository).insertIgnoringDuplicate(eq(1L), eq(10L), eq(7L), eq(playedAt),
                eq(45), any(Instant.class));
    }

    /**
     * The write path must never materialize a {@link uz.orientadvertise.services.domain.model.PlaybackLog}
     * entity. {@code save()} issues its INSERT eagerly under IDENTITY generation, and a unique
     * violation there aborts the transaction before any catch block can run — the exact defect
     * this change removed. Guards against a regression that reintroduces it.
     */
    @Test
    void record_neverUsesTheEntitySavePath() {
        stubInsertReturns(1);

        service.record(device, contentFile, null, Instant.now().minusSeconds(60), 30);

        boolean savedAnything = mockingDetails(repository).getInvocations().stream()
                .anyMatch(i -> i.getMethod().getName().equals("save")
                        || i.getMethod().getName().equals("saveAll"));
        org.junit.jupiter.api.Assertions.assertFalse(savedAnything,
                "the playback write path must go through insertIgnoringDuplicate, not save()");
    }

    // ---------- DATA-01: the rejection bound follows app.retention.playback (v1.0.143) ----------

    @Test
    void record_olderThanTheDefaultNinetyDayWindow_rejected() {
        var playedAt = Instant.now().minus(100, ChronoUnit.DAYS);

        var result = service.record(device, contentFile, null, playedAt, 30);

        var rejected = assertInstanceOf(PlaybackLogResult.Rejected.class, result);
        org.junit.jupiter.api.Assertions.assertTrue(rejected.reason().contains("90-day"),
                "the message must quote the configured window, was: " + rejected.reason());
        verifyNoInsertAttempted();
    }

    /**
     * The bound is the CONFIGURED playback retention, not a constant — a shortened window must
     * start rejecting reports the nightly cleanup would delete, and the message must say 30, not
     * a hard-coded 90.
     */
    @Test
    void record_olderThanAShortenedConfiguredWindow_rejected() {
        var shortened = new RetentionProperties();
        shortened.setPlayback(Duration.ofDays(30));
        var shortWindowService = serviceWith(shortened);

        var result = shortWindowService.record(device, contentFile, null,
                Instant.now().minus(45, ChronoUnit.DAYS), 30);

        var rejected = assertInstanceOf(PlaybackLogResult.Rejected.class, result);
        org.junit.jupiter.api.Assertions.assertTrue(rejected.reason().contains("30-day"),
                "the day count must track the configured window, was: " + rejected.reason());
        verifyNoInsertAttempted();
    }

    @Test
    void record_insideAShortenedConfiguredWindow_accepted() {
        var shortened = new RetentionProperties();
        shortened.setPlayback(Duration.ofDays(30));
        var shortWindowService = serviceWith(shortened);
        stubInsertReturns(1);

        var result = shortWindowService.record(device, contentFile, null,
                Instant.now().minus(20, ChronoUnit.DAYS), 30);

        assertInstanceOf(PlaybackLogResult.Created.class, result);
    }

    @Test
    void sealedResult_patternMatch() {
        var playedAt = Instant.now().plus(5, ChronoUnit.MINUTES);
        var result = service.record(device, contentFile, null, playedAt, 30);

        var message = switch (result) {
            case PlaybackLogResult.Created c -> "created";
            case PlaybackLogResult.Duplicate d -> "duplicate";
            case PlaybackLogResult.Rejected r -> "rejected: " + r.reason();
        };

        assertNotNull(message);
        assert message.startsWith("rejected:");
    }
}
