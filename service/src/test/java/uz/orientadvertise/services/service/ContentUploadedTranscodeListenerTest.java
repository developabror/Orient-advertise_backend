package uz.orientadvertise.services.service;

import java.lang.reflect.Method;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.orientadvertise.services.domain.content.ContentUploadedEvent;
import uz.orientadvertise.services.domain.model.ContentFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ContentUploadedTranscodeListenerTest {

    private TranscodeDispatchService dispatchService;
    private ContentUploadedTranscodeListener listener;

    @BeforeEach
    void setUp() {
        dispatchService = mock(TranscodeDispatchService.class);
        listener = new ContentUploadedTranscodeListener(dispatchService);
    }

    @Test
    void listensAtAfterCommit_notImmediately() throws Exception {
        // This annotation IS the fix. Downgrading it to a plain @EventListener (or moving the phase)
        // puts the dispatch back inside the upload transaction, where the worker reads an
        // uncommitted row on another connection and the file is lost — the v1.0.132 incident.
        Method handler = ContentUploadedTranscodeListener.class
                .getDeclaredMethod("onContentUploaded", ContentUploadedEvent.class);
        var annotation = handler.getAnnotation(TransactionalEventListener.class);
        assertNotNull(annotation, "handler must be a @TransactionalEventListener");
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
    }

    @Test
    void dispatchesFromUploadedStatus() {
        listener.onContentUploaded(new ContentUploadedEvent(42L, false));

        verify(dispatchService).dispatch(42L, ContentFile.Status.UPLOADED, false);
    }

    @Test
    void carriesTheUrgentFlagThroughToTheQueuePriority() {
        listener.onContentUploaded(new ContentUploadedEvent(43L, true));

        verify(dispatchService).dispatch(43L, ContentFile.Status.UPLOADED, true);
    }

    @Test
    void nullEventOrId_isIgnored() {
        listener.onContentUploaded(null);
        listener.onContentUploaded(new ContentUploadedEvent(null, false));

        verify(dispatchService, never()).dispatch(anyLong(), any(), anyBoolean());
    }
}
