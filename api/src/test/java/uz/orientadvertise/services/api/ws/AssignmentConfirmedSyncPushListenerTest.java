package uz.orientadvertise.services.api.ws;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.orientadvertise.services.domain.content.SyncDispatcher;
import uz.orientadvertise.services.service.AssignmentConfirmedEvent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssignmentConfirmedSyncPushListenerTest {

    private SyncDispatcher syncDispatcher;
    private AssignmentConfirmedSyncPushListener listener;

    @BeforeEach
    void setUp() {
        syncDispatcher = mock(SyncDispatcher.class);
        when(syncDispatcher.dispatchSyncToDevices(any(), anyString()))
                .thenReturn(new SyncDispatcher.DispatchResult(0, 0, 0, 0, 0));
        listener = new AssignmentConfirmedSyncPushListener(syncDispatcher);
    }

    @Test
    void onAssignmentConfirmed_nonEmptyIds_dispatchesOnce_withAssignmentConfirmedReason() {
        var event = new AssignmentConfirmedEvent(42L, List.of(10L, 20L, 30L));

        listener.onAssignmentConfirmed(event);

        verify(syncDispatcher, times(1))
                .dispatchSyncToDevices(eq(List.of(10L, 20L, 30L)), eq("assignment-confirmed"));
    }

    @Test
    void onAssignmentConfirmed_emptyIds_shortCircuits_neverCallsDispatcher() {
        // Defensive guard: an event with no devices (e.g. an empty target scope or all
        // devices in scope explicitly excluded) must not pay the cost of a no-op dispatch.
        listener.onAssignmentConfirmed(new AssignmentConfirmedEvent(43L, List.of()));

        verify(syncDispatcher, never()).dispatchSyncToDevices(any(), anyString());
    }

    @Test
    void onAssignmentConfirmed_nullIds_shortCircuits_neverCallsDispatcher() {
        listener.onAssignmentConfirmed(new AssignmentConfirmedEvent(44L, null));

        verify(syncDispatcher, never()).dispatchSyncToDevices(any(), anyString());
    }
}
