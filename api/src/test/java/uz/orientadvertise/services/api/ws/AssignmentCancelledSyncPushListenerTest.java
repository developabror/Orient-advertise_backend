package uz.orientadvertise.services.api.ws;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.content.SyncDispatcher;
import uz.orientadvertise.services.service.AssignmentCancelledEvent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssignmentCancelledSyncPushListenerTest {

    private SyncDispatcher syncDispatcher;
    private AssignmentCancelledSyncPushListener listener;

    @BeforeEach
    void setUp() {
        syncDispatcher = mock(SyncDispatcher.class);
        when(syncDispatcher.dispatchSyncToDevices(any(), anyString()))
                .thenReturn(new SyncDispatcher.DispatchResult(0, 0, 0, 0, 0));
        listener = new AssignmentCancelledSyncPushListener(syncDispatcher);
    }

    @Test
    void onAssignmentCancelled_nonEmptyIds_dispatchesOnce_withAssignmentCancelledReason() {
        var event = new AssignmentCancelledEvent(42L, List.of(10L, 20L, 30L));

        listener.onAssignmentCancelled(event);

        verify(syncDispatcher, times(1))
                .dispatchSyncToDevices(eq(List.of(10L, 20L, 30L)), eq("assignment-cancelled"));
    }

    @Test
    void onAssignmentCancelled_emptyIds_shortCircuits_neverCallsDispatcher() {
        listener.onAssignmentCancelled(new AssignmentCancelledEvent(43L, List.of()));

        verify(syncDispatcher, never()).dispatchSyncToDevices(any(), anyString());
    }

    @Test
    void onAssignmentCancelled_nullIds_shortCircuits_neverCallsDispatcher() {
        listener.onAssignmentCancelled(new AssignmentCancelledEvent(44L, null));

        verify(syncDispatcher, never()).dispatchSyncToDevices(any(), anyString());
    }
}
