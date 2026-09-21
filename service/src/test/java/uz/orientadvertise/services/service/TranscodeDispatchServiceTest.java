package uz.orientadvertise.services.service;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.content.Transcoder;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranscodeDispatchServiceTest {

    private ContentFileRepository repository;
    private Transcoder transcoder;
    private TranscodeDispatchService service;

    @BeforeEach
    void setUp() {
        repository = mock(ContentFileRepository.class);
        transcoder = mock(Transcoder.class);
        service = new TranscodeDispatchService(repository, transcoder);
    }

    @Test
    void claimWon_dispatchesOnce() {
        when(repository.claimForTranscode(eq(1L), eq(ContentFile.Status.UPLOADED), any(Instant.class)))
                .thenReturn(1);

        assertTrue(service.dispatch(1L, ContentFile.Status.UPLOADED, false));

        verify(transcoder).transcodeAsync(1L);
        verify(transcoder, never()).transcodeAsyncUrgent(anyLong());
    }

    @Test
    void claimLost_dispatchesNothing() {
        // The load-bearing negative. Without this guard a sweep racing a live upload would start a
        // second ffmpeg on the same file, doubling peak memory on a host sized for one encode.
        when(repository.claimForTranscode(eq(1L), eq(ContentFile.Status.UPLOADED), any(Instant.class)))
                .thenReturn(0);

        assertFalse(service.dispatch(1L, ContentFile.Status.UPLOADED, false));

        verify(transcoder, never()).transcodeAsync(anyLong());
        verify(transcoder, never()).transcodeAsyncUrgent(anyLong());
    }

    @Test
    void claimHappensBeforeDispatch() {
        // Ordering is the contract: the worker's own start guard re-checks the claim, so dispatching
        // first would make the encode a coin flip on which statement commits sooner.
        when(repository.claimForTranscode(anyLong(), any(), any(Instant.class))).thenReturn(1);

        service.dispatch(3L, ContentFile.Status.FAILED, false);

        var order = inOrder(repository, transcoder);
        order.verify(repository).claimForTranscode(eq(3L), eq(ContentFile.Status.FAILED), any(Instant.class));
        order.verify(transcoder).transcodeAsync(3L);
    }

    @Test
    void urgentFlag_selectsTheFrontOfQueueEntryPoint() {
        when(repository.claimForTranscode(anyLong(), any(), any(Instant.class))).thenReturn(1);

        assertTrue(service.dispatch(2L, ContentFile.Status.FAILED, true));

        verify(transcoder).transcodeAsyncUrgent(2L);
        verify(transcoder, never()).transcodeAsync(anyLong());
    }

    @Test
    void expectedStatus_isPassedThroughAsTheCompareHalf() {
        // A TRANSCODING → TRANSCODING reclaim (crashed encode) must compare against TRANSCODING, not
        // UPLOADED, or the sweeper could never recover a killed job.
        when(repository.claimForTranscode(anyLong(), any(), any(Instant.class))).thenReturn(1);

        service.dispatch(4L, ContentFile.Status.TRANSCODING, false);

        verify(repository).claimForTranscode(eq(4L), eq(ContentFile.Status.TRANSCODING), any(Instant.class));
    }
}
