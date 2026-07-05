package uz.orientadvertise.services.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import uz.orientadvertise.services.domain.model.ContentAssignment;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.model.Device;
import uz.orientadvertise.services.domain.model.PlaybackLog;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.domain.repository.DeviceRepository;
import uz.orientadvertise.services.domain.repository.PlaybackLogRepository;
import uz.orientadvertise.services.domain.repository.PlaylistItemRepository;
import uz.orientadvertise.services.service.PlaybackLogService.PlaybackLogResult;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
        // resolveForDevice defaults to null → no assignment → batch assignment-check is skipped.
        var assignmentService = mock(ContentAssignmentService.class);
        var playlistItemRepository = mock(PlaylistItemRepository.class);
        service = new PlaybackLogService(repository, deviceRepository, contentFileRepository,
                assignmentService, playlistItemRepository, 30);
        device = mock(Device.class);
        when(device.getId()).thenReturn(1L);
        contentFile = mock(ContentFile.class);
        when(contentFile.getId()).thenReturn(10L);
    }

    @Test
    void record_validPlayedAt_createsEntry() {
        var playedAt = Instant.now().minus(5, ChronoUnit.MINUTES);
        when(repository.save(any(PlaybackLog.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.record(device, contentFile, null, playedAt, 30);

        assertInstanceOf(PlaybackLogResult.Created.class, result);
        verify(repository).save(any(PlaybackLog.class));
    }

    @Test
    void record_playedAtInFutureBeyondSkew_rejected() {
        var playedAt = Instant.now().plus(2, ChronoUnit.MINUTES); // 120s > 30s tolerance

        var result = service.record(device, contentFile, null, playedAt, 30);

        assertInstanceOf(PlaybackLogResult.Rejected.class, result);
        verify(repository, never()).save(any());
    }

    @Test
    void record_playedAtSlightlyInFutureWithinSkew_accepted() {
        var playedAt = Instant.now().plus(15, ChronoUnit.SECONDS); // 15s < 30s tolerance
        when(repository.save(any(PlaybackLog.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.record(device, contentFile, null, playedAt, 30);

        assertInstanceOf(PlaybackLogResult.Created.class, result);
    }

    @Test
    void record_exactlyAtSkewBoundary_accepted() {
        var playedAt = Instant.now().plus(29, ChronoUnit.SECONDS); // 29s < 30s
        when(repository.save(any(PlaybackLog.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.record(device, contentFile, null, playedAt, 30);

        assertInstanceOf(PlaybackLogResult.Created.class, result);
    }

    @Test
    void record_duplicate_returnsDuplicateResult() {
        var playedAt = Instant.now().minus(1, ChronoUnit.MINUTES);
        when(repository.save(any(PlaybackLog.class)))
                .thenThrow(new DataIntegrityViolationException("uq_playback_dedup"));

        var result = service.record(device, contentFile, null, playedAt, 30);

        assertInstanceOf(PlaybackLogResult.Duplicate.class, result);
    }

    @Test
    void record_nonDuplicateIntegrityViolation_rethrows() {
        var playedAt = Instant.now().minus(1, ChronoUnit.MINUTES);
        when(repository.save(any(PlaybackLog.class)))
                .thenThrow(new DataIntegrityViolationException("fk_playback_device"));

        org.junit.jupiter.api.Assertions.assertThrows(DataIntegrityViolationException.class, () ->
                service.record(device, contentFile, null, playedAt, 30));
    }

    @Test
    void record_playedAtInPast_accepted() {
        var playedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        when(repository.save(any(PlaybackLog.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.record(device, contentFile, null, playedAt, 60);

        assertInstanceOf(PlaybackLogResult.Created.class, result);
    }

    @Test
    void record_withAssignment_saved() {
        var assignment = mock(ContentAssignment.class);
        var playedAt = Instant.now().minus(10, ChronoUnit.SECONDS);
        when(repository.save(any(PlaybackLog.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.record(device, contentFile, assignment, playedAt, 45);

        assertInstanceOf(PlaybackLogResult.Created.class, result);
        var created = (PlaybackLogResult.Created) result;
        assertNotNull(created.log().getAssignment());
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
