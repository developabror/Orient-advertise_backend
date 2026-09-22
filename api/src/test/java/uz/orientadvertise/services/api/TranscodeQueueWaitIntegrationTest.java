package uz.orientadvertise.services.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uz.orientadvertise.services.Application;
import uz.orientadvertise.services.domain.content.Transcoder;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;
import uz.orientadvertise.services.service.TranscodeDispatchService;
import uz.orientadvertise.services.service.TranscodeSweeper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LOGIC-08 end to end, on the migrated schema with the real sweeper and dispatch service. The
 * transcoder is a stand-in whose only job is to say whether it still holds the file.
 *
 * <p>The review's scenario: several long uploads on a single-width pool, so one job waits in the
 * queue for well over the 20-minute lease. Before the fix each sweep past the lease re-claimed it
 * (one attempt each) and queued a duplicate, and after three rounds it was marked FAILED —
 * "Abandoned after 3 transcode attempt(s)" — without ever having been encoded.
 */
@SpringBootTest(classes = Application.class)
@ActiveProfiles("test")
class TranscodeQueueWaitIntegrationTest {

    @MockitoBean private Transcoder transcoder;

    @Autowired private ContentFileRepository repository;
    @Autowired private TranscodeDispatchService dispatchService;
    @Autowired private TranscodeSweeper sweeper;

    private ContentFile claimedFile() {
        var file = repository.saveAndFlush(
                new ContentFile(null, "long.mp4", "video/mp4", 1024, "raw/wait-" + System.nanoTime(), null));
        dispatchService.dispatch(file.getId(), ContentFile.Status.UPLOADED, false);
        return file;
    }

    private void sweepEvery25MinutesFor100Minutes(Instant from) {
        for (int k = 1; k <= 4; k++) {
            sweeper.sweep(from.plus(25L * k, ChronoUnit.MINUTES), false);
        }
    }

    @Test
    void jobWaitingInTheQueue_survivesFourLeasesOfWaiting_andThenRunsNormally() {
        var file = claimedFile();
        when(transcoder.isPending(file.getId())).thenReturn(true);   // still behind other encodes
        when(transcoder.heldIds()).thenReturn(java.util.Set.of(file.getId()));

        sweepEvery25MinutesFor100Minutes(Instant.now());

        var waiting = repository.findById(file.getId()).orElseThrow();
        assertEquals(ContentFile.Status.TRANSCODING, waiting.getStatus(), "never abandoned");
        assertEquals(0, waiting.getTranscodeAttempts(), "waiting is not an attempt");
        assertNull(waiting.getTranscodeLastError());
        verify(transcoder, times(1)).transcodeAsync(file.getId());      // no duplicates queued

        // Its turn finally comes: the start guard lets exactly one task through.
        Instant start = Instant.now().plus(101, ChronoUnit.MINUTES);
        assertEquals(1, repository.beginTranscode(file.getId(), start));
        assertEquals(0, repository.beginTranscode(file.getId(), start));
        assertEquals(1, repository.findById(file.getId()).orElseThrow().getTranscodeAttempts());
        assertEquals(1, repository.markTranscodeReady(file.getId(), "processed/wait.mp4", 2048L, "sha",
                null, 30, start.plus(5, ChronoUnit.MINUTES)));
    }

    @Test
    void queueEntryThatWasLost_isRequeued_withoutEverBeingMarkedFailed() {
        var file = claimedFile();
        when(transcoder.isPending(file.getId())).thenReturn(false);  // e.g. rejected by a full queue

        sweepEvery25MinutesFor100Minutes(Instant.now());

        var requeued = repository.findById(file.getId()).orElseThrow();
        assertEquals(ContentFile.Status.TRANSCODING, requeued.getStatus());
        assertEquals(0, requeued.getTranscodeAttempts(), "re-queuing a job that never ran costs nothing");
        // The first dispatch plus one requeue per sweep.
        verify(transcoder, times(5)).transcodeAsync(file.getId());
    }
}
