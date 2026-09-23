package uz.orientadvertise.services.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.content.SyncDispatcher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** VG-18: the jump's push, moved out of the jump's own transaction. */
class SyncGroupJumpPushListenerTest {

    private SyncDispatcher syncDispatcher;
    private SyncGroupJumpPushListener listener;

    @BeforeEach
    void setUp() {
        syncDispatcher = mock(SyncDispatcher.class);
        when(syncDispatcher.dispatchSyncToDevices(any(), any()))
                .thenReturn(new SyncDispatcher.DispatchResult(2, 0, 0, 0, 0));
        listener = new SyncGroupJumpPushListener(syncDispatcher);
    }

    @Test
    void tellsExactlyTheMembersTheJumpWasComputedFor() {
        listener.onSyncGroupJumped(new SyncGroupJumpedEvent(9L, List.of(1L, 2L)));

        verify(syncDispatcher).dispatchSyncToDevices(List.of(1L, 2L), "sync-group-jump");
    }

    @Test
    void anEmptyGroupPushesNothing() {
        listener.onSyncGroupJumped(new SyncGroupJumpedEvent(9L, List.of()));
        listener.onSyncGroupJumped(new SyncGroupJumpedEvent(9L, null));

        verifyNoInteractions(syncDispatcher);
    }

    @Test
    void subscribesAfterCommit_withFallbackExecution() throws Exception {
        // The whole fix is WHEN this runs, and that is an annotation — so assert the annotation.
        // AFTER_COMMIT is what stops a member reading the override before it is visible; the
        // fallback covers a jump published outside a transaction, which would otherwise be dropped
        // silently and leave the group waiting for its next /sync.
        var method = SyncGroupJumpPushListener.class.getMethod("onSyncGroupJumped", SyncGroupJumpedEvent.class);
        var annotation = method.getAnnotation(
                org.springframework.transaction.event.TransactionalEventListener.class);
        org.junit.jupiter.api.Assertions.assertNotNull(annotation, "the push must be transaction-scoped");
        org.junit.jupiter.api.Assertions.assertEquals(
                org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT, annotation.phase());
        org.junit.jupiter.api.Assertions.assertTrue(annotation.fallbackExecution());
    }

    @Test
    void aJumpForOneGroupDoesNotTouchAnother() {
        listener.onSyncGroupJumped(new SyncGroupJumpedEvent(9L, List.of(1L)));

        verify(syncDispatcher, never()).dispatchSyncToDevices(eq(List.of(2L)), any());
    }
}
