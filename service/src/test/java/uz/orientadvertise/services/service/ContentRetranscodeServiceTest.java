package uz.orientadvertise.services.service;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.common.exception.ResourceNotFoundException;
import uz.orientadvertise.services.domain.model.ContentFile;
import uz.orientadvertise.services.domain.repository.ContentFileRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentRetranscodeServiceTest {

    private ContentFileRepository repository;
    private TranscodeDispatchService dispatchService;
    private ContentRetranscodeService service;

    @BeforeEach
    void setUp() {
        repository = mock(ContentFileRepository.class);
        dispatchService = mock(TranscodeDispatchService.class);
        service = new ContentRetranscodeService(repository, dispatchService);
    }

    private ContentFile row(long id, ContentFile.Status status) {
        var file = mock(ContentFile.class);
        when(file.getStatus()).thenReturn(status);
        when(file.getStorageKey()).thenReturn("raw/k");
        when(repository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(file));
        return file;
    }

    @Test
    void uploaded_isClaimedAndDispatched() {
        row(1L, ContentFile.Status.UPLOADED);
        when(dispatchService.dispatch(eq(1L), eq(ContentFile.Status.UPLOADED), anyBoolean()))
                .thenReturn(true);

        var result = service.retranscode(1L, "admin");

        assertEquals(1L, result.fileId());
        assertEquals("TRANSCODING", result.status());
        assertEquals("UPLOADED", result.previousStatus());
    }

    @Test
    void failed_isRetriedWithTheAttemptCounterCleared() {
        // A human asking again is not the same event as an automatic retry, and must not be refused
        // because earlier automatic attempts used up the budget. The reset must land BEFORE the
        // claim, or the claim's increment would be wiped out.
        row(2L, ContentFile.Status.FAILED);
        when(dispatchService.dispatch(eq(2L), eq(ContentFile.Status.FAILED), anyBoolean()))
                .thenReturn(true);

        service.retranscode(2L, "operator1");

        var order = inOrder(repository, dispatchService);
        order.verify(repository).resetTranscodeAttempts(eq(2L), any(Instant.class));
        order.verify(dispatchService).dispatch(2L, ContentFile.Status.FAILED, true);
    }

    @Test
    void unknownId_is404() {
        when(repository.findByIdAndDeletedAtIsNull(9L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.retranscode(9L, "admin"));
        verify(dispatchService, never()).dispatch(anyLong(), any(), anyBoolean());
    }

    @Test
    void readyOrTranscodingOrInvalid_is409WithAMessage() {
        // The FE renders `message` on a 409, so it must never be empty. READY needs no work,
        // TRANSCODING is already running, and INVALID cannot be improved by re-running ffmpeg.
        for (var status : new ContentFile.Status[]{ContentFile.Status.READY,
                ContentFile.Status.TRANSCODING, ContentFile.Status.INVALID}) {
            var repo = mock(ContentFileRepository.class);
            var dispatch = mock(TranscodeDispatchService.class);
            var file = mock(ContentFile.class);
            when(file.getStatus()).thenReturn(status);
            when(file.getStorageKey()).thenReturn("raw/k");
            when(repo.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(file));

            var isolated = new ContentRetranscodeService(repo, dispatch);
            var ex = assertThrows(IllegalStateException.class, () -> isolated.retranscode(1L, "admin"),
                    "status " + status + " must be refused");
            assertTrue(ex.getMessage() != null && ex.getMessage().contains(status.name()),
                    "409 message names the offending status: " + ex.getMessage());
            verify(dispatch, never()).dispatch(anyLong(), any(), anyBoolean());
        }
    }

    @Test
    void missingRawObject_is409() {
        var file = mock(ContentFile.class);
        when(file.getStatus()).thenReturn(ContentFile.Status.FAILED);
        when(file.getStorageKey()).thenReturn(null);
        when(repository.findByIdAndDeletedAtIsNull(4L)).thenReturn(Optional.of(file));

        var ex = assertThrows(IllegalStateException.class, () -> service.retranscode(4L, "admin"));
        assertTrue(ex.getMessage().contains("no raw object"), ex.getMessage());
        verify(dispatchService, never()).dispatch(anyLong(), any(), anyBoolean());
    }

    @Test
    void lostClaimRace_is409RatherThanAFalseSuccess() {
        // Something else claimed the row between our read and the CAS. We did not start anything,
        // so reporting 200 would tell the operator a transcode began when it did not.
        row(5L, ContentFile.Status.UPLOADED);
        when(dispatchService.dispatch(eq(5L), eq(ContentFile.Status.UPLOADED), anyBoolean()))
                .thenReturn(false);

        var ex = assertThrows(IllegalStateException.class, () -> service.retranscode(5L, "admin"));
        assertTrue(ex.getMessage().contains("already running"), ex.getMessage());
    }
}
